///MAIN=Kotlin_scriptKt
///COMPILER=org.jetbrains.kotlin:kotlin-stdlib:1.3.10::sha256=9b9650550fac559f7db64d988123399ea3da7cb776bfb13b9a3ed818eef26969
///COMPILER=org.jetbrains.kotlin:kotlin-reflect:1.3.10::sha256=764efe8190053c6916c7c9985cfeb722b4616a0bccdf4170768abd947efe2632
///COMPILER=org.jetbrains.kotlin:kotlin-compiler:1.3.10::sha256=84d8d64c624790b52fd4890d5512d58b156aa076c7d8618576a875fc2ef8d540
///COMPILER=org.jetbrains.kotlin:kotlin-script-runtime:1.3.10::sha256=e05a2cad07ff8b980c51a94ddb50cb5addfedd699f468853ca5c960192dd90ef

import java.io.*
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipOutputStream

const val kotlinCompilerMain = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

enum class Scope {
    Compiler,
    CompilerPlugin,
    CompileOnly,
    Compile,
    Runtime
}

data class Dependency(
    val group: String,
    val id: String,
    val version: String,
    val ext: String,
    val sha256: String?,
    val scope: Scope = Scope.Compiler
)

data class Script(
    val path: String,
    val checksum: String? = null,
    val data: ByteBuffer? = null
)

data class MetaData(
    val main: String,
    val mainScript: Script,
    val inc: List<Script>,
    val dep: List<Dependency>,
    val compilerArgs: List<String> = listOf(),
    val compilerExitCode: Int = 0,
    val compilerErrors: List<String> = listOf()
)

fun parseDependency(spec: String): Dependency {
    val parts = spec.split(':')
    if (parts.size < 3) throw IllegalArgumentException("invalid dependency spec: $spec")
    val ext = parts.getOrNull(3)?.trim() ?: ""
    val checksum = parts.getOrNull(4)?.split('=', limit = 2)
    val sha256 = when (checksum?.firstOrNull()) {
        "sha256" -> checksum.getOrNull(1)?.trim()
        else -> null
    }
    return Dependency(
        parts[0], parts[1], parts[2],
        if (ext.isEmpty()) "jar" else ext,
        sha256
    )
}

fun Dependency.toSpec(): String =
    "$group:$id:$version:${if (ext != "jar") ext else ""}${if (sha256 != null) ":sha256=$sha256" else ""}"

fun parseMetaData(scriptFileName: String, `in`: InputStream): MetaData {
    var main: String? = null
    val inc = mutableListOf<String>()
    val dep = mutableListOf<Dependency>()
    val chk = mutableListOf<String>()
    val cargs = mutableListOf<String>()
    var rc: Int? = null
    val errors = mutableListOf<String>()
    `in`.bufferedReader().lineSequence().forEach { line ->
        when {
            line.startsWith("///MAIN=") -> main = line.substring(8).trim()
            line.startsWith("///INC=") -> inc.add(line.substring(7).trim())
            line.startsWith("///CHK=") -> chk.add(line.substring(10).trim())
            line.startsWith("///DEP=") -> dep.add(
                parseDependency(line.substring(7).trim()).copy(
                    scope = Scope.Compile
                )
            )
            line.startsWith("///CDEP=") -> dep.add(
                parseDependency(line.substring(8).trim()).copy(
                    scope = Scope.CompileOnly
                )
            )
            line.startsWith("///RDEP=") -> dep.add(
                parseDependency(line.substring(8).trim()).copy(
                    scope = Scope.Runtime
                )
            )
            line.startsWith("///COMPILER=") -> dep.add(
                parseDependency(line.substring(12).trim()).copy(
                    scope = Scope.Compiler
                )
            )
            line.startsWith("///PLUGIN=") -> dep.add(
                parseDependency(line.substring(10).trim()).copy(
                    scope = Scope.CompilerPlugin
                )
            )
            line.startsWith("///CARG=") -> cargs.add(line.substring(8).trim())
            line.startsWith("///RC=") -> rc = line.substring(6).trim().toInt()
            line.startsWith("///ERROR=") -> errors.add(line.substring(9).trim())
            else -> {
            }
        }
    }
    val mainScript = Script(scriptFileName, chk.firstOrNull())
    val scripts = inc.mapIndexed { index, s ->
        Script(s, chk.getOrNull(index + 1))
    }
    return MetaData(
        main ?: throw IllegalArgumentException("missing MAIN in meta data"),
        mainScript = mainScript,
        inc = scripts,
        dep = dep,
        compilerArgs = cargs,
        compilerExitCode = rc ?: 0,
        compilerErrors = errors
    )
}

fun parseMetaData(scriptFileName: String, file: File): MetaData {
    FileInputStream(file).use { `in` ->
        return parseMetaData(scriptFileName, `in`)
    }
}

fun parseMetaData(scriptFileName: String, zipFile: File, entryName: String): MetaData {
    val env = mapOf<String, String>()
    val uri = URI.create("jar:" + zipFile.toURI())
    FileSystems.newFileSystem(uri, env).use { fs ->
        val nf = fs.getPath(entryName)
        Files.newInputStream(nf, StandardOpenOption.READ).use { input ->
            return parseMetaData(scriptFileName, input)
        }
    }

}

fun MetaData.store(out: OutputStream) {
    val w = out.bufferedWriter()
    w.write("///MAIN=$main\n")
    w.write("///CHK=${mainScript.checksum}\n")
    inc.forEach { s ->
        w.write("///INC=${s.path}\n")
        w.write("///CHK=${s.checksum}\n")
    }
    dep.forEach { d ->
        val k = when (d.scope) {
            Scope.Compiler -> "COMPILER"
            Scope.CompilerPlugin -> "PLUGIN"
            Scope.CompileOnly -> "CDEP"
            Scope.Runtime -> "RDEP"
            else -> "DEP"
        }
        w.write("///$k=${d.toSpec()}\n")
    }
    compilerArgs.forEach { w.write("///CARG=$it\n") }
    w.write("///RC=$compilerExitCode\n")
    compilerErrors.forEach { w.write("///ERROR=$it\n") }
    w.flush()
}

fun MetaData.storeToZipFile(zipFile: File, entryName: String = "kotlin_script.metadata") {
    val env = mapOf<String, String>()
    val uri = URI.create("jar:" + zipFile.toURI())
    FileSystems.newFileSystem(uri, env).use { fs ->
        val nf = fs.getPath(entryName)
        Files.newOutputStream(nf, StandardOpenOption.CREATE).use { out ->
            store(out)
        }
    }
}

fun loadScript(f: File): Script {
    val data = try {
        FileInputStream(f).use { `in` ->
            ByteBuffer.wrap(`in`.readBytes()).asReadOnlyBuffer()
        }
    } catch (_: FileNotFoundException) {
        ByteBuffer.allocate(0)
    }
    return Script(f.path, "sha256=${data.sha256()}", data)
}

fun ByteBuffer.sha256(): String {
    val oldPosition = position()
    try {
        position(0)
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 4)
        while (true) {
            val remaining = remaining()
            if (remaining <= 0) break
            val readNow = minOf(buffer.size, remaining)
            get(buffer, 0, readNow)
            md.update(buffer, 0, readNow)
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    } finally {
        position(oldPosition)
    }
}

fun String.absoluteToSubPath(): String {
    if (startsWith(File.separator)) {
        return removePrefix(File.separator)
    }
    if (getOrNull(1) == ':' && getOrNull(2) == File.separatorChar) {
        return "${this[0]}${substring(2)}"
    }
    throw IllegalArgumentException("invalid absolute path: $this")
}

class KotlinScript(
    val javaHome: File,
    val kotlinHome: File?,
    val cacheDir: File,
    val mavenRepoUrl: String,
    val mavenRepoCache: File?,
    val localRepo: File,
    val scriptFile: File
) {
    val compilerMetaData = javaClass
        .getResourceAsStream("kotlin_script.compiler.metadata")?.let {
            parseMetaData(scriptFile.path, it)
        }

    val mainScript = loadScript(scriptFile)

    val jarFile = File(
        cacheDir, "cache" + File.separator + scriptFile
            .absolutePath
            .absoluteToSubPath()
            .removeSuffix(".kt")
                + ".jar"
    )

    private val cachedMetaData: MetaData? = when {
        !jarFile.exists() -> null
        else -> {
            try {
                parseMetaData(scriptFile.path, jarFile, "kotlin_script.metadata")
            } catch (_: Exception) {
                null
            }
        }
    }

    private val cachedScripts = mutableMapOf<String, Script>()

    fun resolveInc(path: String): Script {
        return cachedScripts.computeIfAbsent(path) { p ->
            loadScript(scriptFile.toPath().resolve(p).toFile())
        }
    }

    fun resolveLib(dep: Dependency): File {
        val subPath = dep.group.replace(".", "/") + "/" + dep.id + "/" +
                dep.version + "/" + "${dep.id}-${dep.version}.${dep.ext}"
        if (mavenRepoCache != null) {
            val f = File(mavenRepoCache, subPath)
            if (f.exists()) return f
        }
        val f = File(localRepo, subPath)
        if (f.exists()) return f
        val tmp = File(f.absolutePath + "~")
        val md = MessageDigest.getInstance("SHA-256")
        Files.createDirectories(tmp.parentFile.toPath())
        FileOutputStream(tmp).use { out ->
            URL("$mavenRepoUrl/$subPath").openStream().use { `in` ->
                val buffer = ByteArray(1024 * 4)
                while (true) {
                    val n = `in`.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        val sha256 = md.digest().joinToString("") { String.format("%02x", it) }
        if (dep.sha256 != null && dep.sha256 != sha256) {
            Files.delete(tmp.toPath())
            error("unexpected sha256=$sha256 for $dep")
        }
        Files.move(
            tmp.toPath(), f.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        return f
    }

    fun kotlinCompilerArgs(compilerDeps: List<Dependency>): Array<String> {
        if (kotlinHome != null) {
            TODO()
            /*
            val kotlinBinDir = File(kotlinHome, "bin")
            val kotlinExe = listOf("kotlinc.exe", "kotlinc", "kotlinc.bat")
                .map { File(kotlinBinDir, it) }
                .firstOrNull(File::exists)
            if (kotlinExe != null) return arrayOf(
                kotlinExe.absolutePath,
                "-jdk-home",
                javaHome.absolutePath,
                "-kotlin-home",
                kotlinHome.absolutePath
            )
            error("kotlinc not found in kotlin.home: $kotlinHome")
            */
        }
        val kotlinVersions = compilerDeps.map { d ->
            if (d.group == "org.jetbrains.kotlin" && d.id == "kotlin-stdlib") {
                d.version.trim()
            } else ""
        }.toSet()
        val kotlinVersion = kotlinVersions.filterNot { it.isEmpty() }.singleOrNull()
            ?: error("conflicting kotlin versions in compiler dependencies")
        val tmpKotlinHome = File(cacheDir, "kotlin-compiler-$kotlinVersion${File.separator}kotlinc")
        Files.createDirectories(File(tmpKotlinHome, "lib").toPath())
        val compilerClassPath = compilerDeps.map { d ->
            val f = resolveLib(d)
            val copy = File(tmpKotlinHome, "lib${File.separator}${d.id}.${d.ext}")
            if (!copy.exists()) {
                Files.copy(f.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            copy
        }
        return arrayOf(
            File(javaHome, "bin${File.separator}java").absolutePath,
            "-Djava.awt.headless=true",
            "-cp", compilerClassPath.joinToString(File.pathSeparator) { it.absolutePath },
            kotlinCompilerMain,
            "-kotlin-home", tmpKotlinHome.absolutePath
        )
    }

    fun compile(
        metaDataEntryName: String = "kotlin_script.metadata",
        addArgs: List<String> = emptyList()
    ): MetaData {
        val metaData = parseMetaData(scriptFile.path, scriptFile)
        val compilerDepsOverride = metaData.dep.filter { it.scope == Scope.Compiler }
        val compilerDeps = when {
            compilerDepsOverride.isNotEmpty() -> compilerDepsOverride
            compilerMetaData != null -> compilerMetaData.dep.filter { it.scope == Scope.Compiler }
            else -> error("no compiler metadata in classpath!")
        }
        val scriptClassPath = metaData.dep
            .filter { d -> d.scope in listOf(Scope.Compile, Scope.CompileOnly) }
            .map { d -> resolveLib(d) }
        val tmpJarFile = File("${jarFile.absolutePath.removeSuffix(".jar")}~.jar")
        Files.createDirectories(tmpJarFile.parentFile.toPath())
        val permissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        try {
            Files.createFile(tmpJarFile.toPath(), PosixFilePermissions.asFileAttribute(permissions))
        } catch (_: UnsupportedOperationException) {
        } catch (ex: FileAlreadyExistsException) {
            Files.setPosixFilePermissions(tmpJarFile.toPath(), permissions)
        }
        System.err.println("--> recompiling script")
        val scriptClassPathArgs = if (scriptClassPath.isEmpty()) emptyList() else listOf(
            "-classpath", scriptClassPath.joinToString(File.pathSeparator)
        )
        //TODO cached scripts should be used for compilation!
        val incArgs = metaData.inc.map { inc ->
            scriptFile.toPath().resolve(inc.path).toString()
        }
        val compilerArgs: List<String> = listOf(
            *kotlinCompilerArgs(compilerDeps),
            *addArgs.toTypedArray(),
            *scriptClassPathArgs.toTypedArray(),
            "-d", tmpJarFile.absolutePath,
            scriptFile.absolutePath,
            *incArgs.toTypedArray()
        )
        System.err.println("+ ${compilerArgs.joinToString(" ")}")
        val compilerProcess = ProcessBuilder(*compilerArgs.toTypedArray())
            .redirectErrorStream(true)
            .start()
        compilerProcess.outputStream.close()
        val compilerErrors = String(compilerProcess.inputStream.readBytes())
        val rc = compilerProcess.waitFor()
        val finalMetaData = metaData.copy(
            mainScript = mainScript,
            inc = metaData.inc.map { resolveInc(it.path) },
            dep = if (compilerDepsOverride.isEmpty()) {
                compilerDeps + metaData.dep
            } else {
                metaData.dep
            },
            compilerExitCode = rc,
            compilerErrors = compilerErrors.split("\n")
        )
        if (rc != 0) {
            FileOutputStream(tmpJarFile).use { out ->
                ZipOutputStream(out).close()
            }
        }
        finalMetaData.storeToZipFile(tmpJarFile, metaDataEntryName)
        Files.move(
            tmpJarFile.toPath(), jarFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        return finalMetaData
    }

    fun main(args: Array<String>) {
        val metaData = if (
            cachedMetaData == null ||
            // check if script file changed
            cachedMetaData.mainScript.checksum != mainScript.checksum ||
            // check if include files changed
            cachedMetaData.inc.map { it.checksum } !=
            cachedMetaData.inc.map { resolveInc(it.path).checksum } ||
            // check if compiler changed
            (compilerMetaData != null && compilerMetaData.dep !=
                    cachedMetaData.dep.filter { it.scope == Scope.Compiler })
        ) {
            compile()
        } else {
            cachedMetaData
        }

        if (metaData.compilerExitCode != 0) {
            metaData.compilerErrors.forEach(System.err::println)
            System.exit(metaData.compilerExitCode)
        }

        if (compilerMetaData == null) {
            TODO("forking not implemented")
        }

        val classPath = listOf(jarFile) + metaData.dep
            .filter { d -> d.scope == Scope.Compile || d.scope == Scope.Runtime }
            .map { d -> resolveLib(d) }
        val classLoader = URLClassLoader(classPath.map { it.toURI().toURL() }.toTypedArray())
        val mainClass = classLoader.loadClass(metaData.main)
        System.setProperty("kotlin_script.scriptFile.absolutePath", scriptFile.absolutePath)

        mainClass
            .getDeclaredMethod("main", Array<String>::class.java)
            .invoke(null, args)
    }
}

fun main(args: Array<String>) {
    val javaHome = File(
        System.getProperty("java.home").removeSuffix("${File.separator}jre")
    )
    val kotlinHome = System.getProperty("kotlin.home")?.let(::File)
    val userHome = File(System.getProperty("user.home") ?: error("user.home system property not set"))
    val cacheDir = File(userHome, ".kotlin_script")
    val localRepo = File(userHome, ".m2${File.separator}repository")
    val mavenRepoUrl = System.getProperty("maven.repo.url") ?: "https://repo1.maven.org/maven2"
    val mavenRepoCache = System.getProperty("maven.repo.cache")?.let(::File)

    val scriptArg = args.indexOfFirst { !it.startsWith("--") }
    if (scriptArg < 0) error("missing script filename")
    val scriptFileName = args[scriptArg]
    val scriptFile = File(scriptFileName).canonicalFile
    if (!scriptFile.exists()) error("file not found: '$scriptFile'")

    val flags = args.sliceArray(0 until scriptArg).toList()

    val ks = KotlinScript(
        javaHome = javaHome,
        kotlinHome = kotlinHome,
        cacheDir = cacheDir,
        mavenRepoUrl = mavenRepoUrl,
        mavenRepoCache = mavenRepoCache,
        localRepo = localRepo,
        scriptFile = scriptFile
    )

    when {
        flags.isEmpty() -> {
            ks.main(args.sliceArray((scriptArg + 1) until args.size))
            return
        }
        flags == listOf("--install") -> {
            val metaData = ks.compile("kotlin_script.compiler.metadata", listOf("-include-runtime"))
            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                System.exit(metaData.compilerExitCode)
            }
            println(ks.jarFile)
            return
        }
        else -> error("invalid flags: flags")
    }
}
