package kotlin_script

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val kotlinCompilerMain =
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

class KotlinScript(
        val javaHome: File,
        val kotlinScriptHome: File,
        val mavenRepoUrl: String,
        val mavenRepoCache: File?,
        val localRepo: File,
        val trace: Boolean
) {
    private val manifest = javaClass.classLoader
            .getResources(manifestPath)
            .asSequence()
            .flatMap { url ->
                url.openStream().use { `in` ->
                    Manifest(`in`).mainAttributes.filter { entry ->
                        "kotlin" in entry.key.toString().toLowerCase()
                    }.map { entry ->
                        entry.key.toString() to entry.value.toString()
                    }.asSequence()
                }
            }.toMap()

    private val compilerClassPath = manifest["Kotlin-Compiler-Class-Path"]
            ?.split(' ')
            ?.map { spec -> parseDependency(spec).copy(scope = Scope.Runtime) }
            ?: error("no compiler classpath in manifest")

    private fun resolveLib(dep: Dependency): File {
        val subPath = dep.subPath
        if (mavenRepoCache != null) {
            val f = File(mavenRepoCache, subPath)
            if (f.exists()) return f
        }
        val f = File(localRepo, subPath)
        if (f.exists()) return f
        Files.createDirectories(f.toPath().parent)
        val tmp = Files.createTempFile(
                f.toPath().parent,
                f.name + "~",
                "")
        try {
            if (trace) println("++ fetch $mavenRepoUrl/$subPath")
            val md = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(tmp, StandardOpenOption.WRITE).use { out ->
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
            val sha256 = md.digest().joinToString("") {
                String.format("%02x", it)
            }
            if (dep.sha256 != null && dep.sha256 != sha256) {
                error("unexpected sha256=$sha256 for $dep")
            }
            Files.move(
                    tmp, f.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            )
        } catch(ex: Throwable) {
            try {
                Files.delete(tmp)
            } catch (_: java.nio.file.NoSuchFileException) {
            }
            throw ex
        }
        return f
    }

    private fun kotlinCompilerArgs(): Array<String> {
        val kotlinVersions = compilerClassPath.map { d ->
            if (d.groupId == "org.jetbrains.kotlin" &&
                    d.artifactId == "kotlin-stdlib") {
                d.version.trim()
            } else ""
        }.toSet()
        val kotlinVersion = kotlinVersions.singleOrNull(String::isNotEmpty)
                ?: error("conflicting kotlin versions in compiler dependencies")
        val tmpKotlinHome = File(kotlinScriptHome,
                "kotlin-compiler-$kotlinVersion/kotlinc")
        Files.createDirectories(File(tmpKotlinHome, "lib").toPath())
        val cp = compilerClassPath.joinToString(File.pathSeparator) { d ->
            val f = resolveLib(d)
            val copy = File(tmpKotlinHome, "lib/${d.artifactId}.${d.type}")
            if (!copy.exists()) {
                Files.copy(f.toPath(), copy.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
            }
            //TODO use correct quoting
            copy.absolutePath
        }
        return arrayOf(
                File(javaHome, "bin/java").absolutePath,
                "-Djava.awt.headless=true",
                "-cp", cp,
                kotlinCompilerMain,
                "-kotlin-home", tmpKotlinHome.absolutePath,
                "-jvm-target", System.getProperty("java.vm.specification.version")
        )
    }

    fun compile(scriptFile: File, outFile: File): MetaData {
        val metaData = parseMetaData(scriptFile)

        val runtimeClassPath = compilerClassPath.filter {
            it.artifactId in listOf("kotlin-stdlib", "kotlin-reflect")
        }
        val compileClassPath = (runtimeClassPath + metaData.dep.filter { d ->
            d.scope == Scope.Compile
        }).map { d -> resolveLib(d) }
        Files.createDirectories(outFile.parentFile.toPath())
        val permissions = PosixFilePermissions.asFileAttribute(setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ))
        try {
            Files.createFile(outFile.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
        } catch (ex: FileAlreadyExistsException) {
            Files.setPosixFilePermissions(outFile.toPath(), permissions.value())
        }
        val compileClassPathArgs = when {
            compileClassPath.isEmpty() -> emptyList()
            else -> listOf(
                    "-cp", compileClassPath.joinToString(File.pathSeparator)
            )
        }
        // TODO cached scripts should be used for compilation
        // --> use a temp dir for compilation and also attach script
        //     sources to jar (or it is possible with embedded compiler?)
        val incArgs = metaData.inc.map { inc ->
            File(scriptFile.parentFile, inc.path).path
        }
        val scriptFileArgs = when (scriptFile.name.endsWith(".kt")) {
            true -> listOf(scriptFile.absolutePath)
            else -> emptyList()
        }
        val (rc, compilerErrors) = if (!scriptFileArgs.isEmpty()
                || !incArgs.isEmpty()) {
            val compilerArgs: List<String> = listOf(
                    *kotlinCompilerArgs(),
                    *compileClassPathArgs.toTypedArray(),
                    "-d", outFile.toString(),
                    *scriptFileArgs.toTypedArray(),
                    *incArgs.toTypedArray()
            )
            if (trace) println("++ ${compilerArgs.joinToString(" ")}")
            val compilerProcess = ProcessBuilder(*compilerArgs.toTypedArray())
                    .redirectErrorStream(true)
                    .start()
            compilerProcess.outputStream.close()
            val compilerErrors = compilerProcess.inputStream.use { `in` ->
                String(`in`.readBytes())
            }
            val rc = compilerProcess.waitFor()
            rc to compilerErrors
        } else {
            FileOutputStream(outFile).use { out ->
                ZipOutputStream(out).close()
            }
            0 to ""
        }
        val finalMetaData = metaData.copy(
                dep = metaData.dep,
                compilerExitCode = rc,
                compilerErrors = compilerErrors.split("\n")
        )
        if (rc != 0) {
            // remove output file
            try {
                Files.delete(outFile.toPath())
            } catch (_: java.nio.file.NoSuchFileException) {
            }
        } else {
            finalMetaData.storeToZipFile(
                    outFile,
                    "kotlin_script.metadata")
            updateManifest(outFile,
                    mainClass = finalMetaData.main,
                    cp = (runtimeClassPath + finalMetaData.dep.filter {
                        it.scope in listOf(Scope.Compile, Scope.Runtime)
                    }).map {
                        val libFile = resolveLib(it)
                        outFile.toPath().toAbsolutePath().parent
                                .relativize(libFile.toPath().toAbsolutePath())
                                .joinToString("/")
                    })
        }
        return finalMetaData
    }

    companion object {

        private fun findJavaHome(): File {
            val javaHome = File(System.getProperty("java.home"))
            return if (javaHome.path.endsWith("${File.separator}jre") &&
                    File(javaHome, "../lib/tools.jar").exists()) {
                // detected jdk
                File(javaHome.path.removeSuffix("${File.separator}jre"))
            } else {
                javaHome
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val javaHome = findJavaHome()
            val userHome = File(System.getProperty("user.home")
                    ?: error("user.home system property not set"))
            val kotlinScriptHome = System.getProperty("kotlin_script.home")
                    ?.let(::File)
                    ?: File(userHome, ".kotlin_script")
            val localRepo = System.getProperty("maven.repo.local")
                    ?.let(::File)
                    ?: File(userHome, ".m2${File.separator}repository")
            val mavenRepoUrl = System.getProperty("maven.repo.url")
                    ?: "https://repo1.maven.org/maven2"
            val mavenRepoCache = System.getProperty("maven.repo.cache")
                    ?.let(::File)

            val flags = mutableMapOf<String, String?>()
            var k = 0
            while (k < args.size) {
                val key = args[k]
                if (!key.startsWith("-")) break
                k++
                val v = when (key) {
                    "-c", "-d", "-M" -> args.getOrNull(k).also { k++ }
                    "-version", "-x" -> "yes"
                    else -> error("unknown option: $k")
                }
                flags[key] = v
            }

            val ks = KotlinScript(
                    javaHome = javaHome,
                    kotlinScriptHome = kotlinScriptHome,
                    mavenRepoUrl = mavenRepoUrl,
                    mavenRepoCache = mavenRepoCache,
                    localRepo = localRepo,
                    trace = flags["-x"] == "yes"
            )

            val scriptFileName = args.getOrNull(k)
                    ?: error("missing script path")

            val scriptFile = File(scriptFileName).canonicalFile
            if (!scriptFile.exists()) error("file not found: '$scriptFile'")

            val target = flags["-d"] ?: error("missing -d option")
            val targetFile = File(target)
            val metaData = ks.compile(scriptFile, targetFile)

            when (val storeMetaData = flags["-M"]) {
                null -> {}
                else -> metaData.storeToFile(File(storeMetaData))
            }

            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                System.exit(metaData.compilerExitCode)
            }
        }

    }
}
