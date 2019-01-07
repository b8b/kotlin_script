import java.io.*
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipOutputStream

object KotlinScript {

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
    ) {
        fun toSpec(): String = "$group:$id:$version:" +
                (if (ext != ".jar") ext else "") +
                if (sha256 != null) ":sha256=$sha256" else ""
    }

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
    ) {
        fun store(out: OutputStream) {
            val w = out.bufferedWriter(Charsets.UTF_8)
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

        fun storeToZipFile(zipFile: File,
                           entryName: String = "kotlin_script.metadata") {
            val env = mapOf<String, String>()
            val uri = URI.create("jar:" + zipFile.toURI())
            FileSystems.newFileSystem(uri, env).use { fs ->
                val nf = fs.getPath(entryName)
                Files.newOutputStream(nf, StandardOpenOption.CREATE)
                        .use { out ->
                            store(out)
                        }
            }
        }
    }

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
                if (ext.isEmpty()) ".jar" else ext,
                sha256
        )
    }

    fun parseMetaData(scriptFileName: String, `in`: InputStream): MetaData {
        val metaDataMap = `in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { line ->
            line.startsWith("///")
        }.map { line ->
            line.removePrefix("///").split('=', limit = 2)
        }.groupBy(
                keySelector = { pair -> pair.first().removePrefix("///") },
                valueTransform = { pair -> pair.getOrNull(1) ?: "" }
        )
        val mainScript = Script(scriptFileName, metaDataMap["CHK"]?.firstOrNull())
        val scripts = metaDataMap["INC"]?.mapIndexed { index, s ->
            Script(s, metaDataMap["CHK"]?.getOrNull(index + 1))
        }
        val dep = listOf(
                "DEP" to Scope.Compile,
                "CDEP" to Scope.CompileOnly,
                "RDEP" to Scope.Runtime,
                "COMPILER" to Scope.Compiler,
                "PLUGIN" to Scope.CompilerPlugin
        ).flatMap { scope ->
            metaDataMap[scope.first]?.map { spec ->
                parseDependency(spec).copy(scope = scope.second)
            } ?: emptyList()
        }
        return MetaData(
                metaDataMap["MAIN"]?.singleOrNull()
                        ?: throw IllegalArgumentException("missing MAIN in meta data"),
                mainScript = mainScript,
                inc = scripts ?: emptyList(),
                dep = dep,
                compilerArgs = metaDataMap["CARG"] ?: emptyList(),
                compilerExitCode = metaDataMap["RC"]?.singleOrNull()?.toInt()
                        ?: 0,
                compilerErrors = metaDataMap["ERROR"] ?: emptyList()
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

    fun updateManifest(zipFile: File, mainClass: String, cp: List<String>) {
        val env = mapOf<String, String>("create" to "false")
        val uri = URI.create("jar:" + zipFile.toURI())
        FileSystems.newFileSystem(uri, env).use { fs ->
            val nf = fs.getPath("META-INF/MANIFEST.MF")
            val manifest = try {
                Files.newInputStream(nf, StandardOpenOption.READ).use { `in` ->
                    Manifest(`in`)
                }
            } catch (_: NoSuchFileException) {
                Manifest()
            }
            manifest.mainAttributes.apply {
                putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0")
                put(Attributes.Name.MAIN_CLASS, mainClass)
                if (cp.isNotEmpty()) {
                    put(Attributes.Name.CLASS_PATH, cp.joinToString(" "))
                }
            }
            Files.createDirectories(nf.parent)
            Files.newOutputStream(nf, StandardOpenOption.CREATE).use { out ->
                manifest.write(out)
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

    fun debug(msg: String) = System.console()?.printf("%s\n", msg)

    class KotlinScript(
            val javaHome: File,
            val kotlinScriptHome: File,
            val mavenRepoUrl: String,
            val mavenRepoCache: File?,
            val localRepo: File,
            val scriptFile: File,
            val outFile: File
    ) {
        val compilerMetaData = javaClass
                .getResourceAsStream("kotlin_script.compiler.metadata")?.let {
                    parseMetaData(scriptFile.path, it)
                }

        val mainScript = loadScript(scriptFile)

        private val cachedMetaData: MetaData? = when {
            !outFile.exists() -> null
            else -> {
                try {
                    parseMetaData(scriptFile.path, outFile, "kotlin_script.metadata")
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
                    dep.version + "/" + "${dep.id}-${dep.version}${dep.ext}"
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
            val kotlinVersions = compilerDeps.map { d ->
                if (d.group == "org.jetbrains.kotlin" && d.id == "kotlin-stdlib") {
                    d.version.trim()
                } else ""
            }.toSet()
            val kotlinVersion = kotlinVersions.filterNot { it.isEmpty() }.singleOrNull()
                    ?: error("conflicting kotlin versions in compiler dependencies")
            val tmpKotlinHome = File(kotlinScriptHome,
                    "kotlin-compiler-$kotlinVersion${File.separator}kotlinc")
            Files.createDirectories(File(tmpKotlinHome, "lib").toPath())
            val compilerClassPath = compilerDeps.joinToString(File.pathSeparator) { d ->
                val f = resolveLib(d)
                val copy = File(tmpKotlinHome, "lib${File.separator}${d.id}${d.ext}")
                if (!copy.exists()) {
                    Files.copy(f.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                copy.absolutePath
            }
            return arrayOf(
                    File(javaHome, "bin${File.separator}java").absolutePath,
                    "-Djava.awt.headless=true",
                    "-cp", compilerClassPath,
                    kotlinCompilerMain
            )
        }

        fun compile(metaDataEntryName: String = "kotlin_script.metadata"): MetaData {
            val metaData = parseMetaData(scriptFile.path, scriptFile)
            val compilerDepsOverride = metaData.dep.filter { it.scope == Scope.Compiler }
            val compilerDeps = when {
                compilerDepsOverride.isNotEmpty() -> compilerDepsOverride
                compilerMetaData != null -> compilerMetaData.dep.filter { it.scope == Scope.Compiler }
                else -> error("no compiler metadata in classpath!")
            }
            val rtDeps = compilerDeps.filter {
                it.id in listOf("kotlin-stdlib", "kotlin-reflect")
            }
            val scriptClassPath = (rtDeps + metaData.dep.filter { d ->
                d.scope in listOf(Scope.Compile, Scope.CompileOnly)
            }).map { d -> resolveLib(d) }
            val tmpJarFile = File("${outFile.absolutePath.removeSuffix(".jar")}~.jar")
            Files.createDirectories(tmpJarFile.parentFile.toPath())
            val permissions = PosixFilePermissions.asFileAttribute(setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ))
            try {
                Files.createFile(tmpJarFile.toPath(), permissions)
            } catch (_: UnsupportedOperationException) {
            } catch (ex: FileAlreadyExistsException) {
                Files.setPosixFilePermissions(tmpJarFile.toPath(), permissions.value())
            }
            debug("--> recompiling script")
            val scriptClassPathArgs = when {
                scriptClassPath.isEmpty() -> emptyList()
                else -> listOf(
                        "-cp", scriptClassPath.joinToString(File.pathSeparator)
                )
            }
            // TODO cached scripts should be used for compilation
            // --> use a temp dir for compilation and also attach script sources to jar
            val incArgs = metaData.inc.map { inc ->
                scriptFile.toPath().parent.resolve(inc.path).toString()
            }
            val compilerArgs: List<String> = listOf(
                    *kotlinCompilerArgs(compilerDeps),
                    *scriptClassPathArgs.toTypedArray(),
                    "-d", tmpJarFile.absolutePath,
                    scriptFile.absolutePath,
                    *incArgs.toTypedArray()
            )
            debug("+ ${compilerArgs.joinToString(" ")}")
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
            updateManifest(tmpJarFile,
                    mainClass = finalMetaData.main,
                    cp = (rtDeps + finalMetaData.dep.filter {
                        it.scope in listOf(Scope.Compile, Scope.Runtime)
                    }).map {
                        outFile.parentFile.absoluteFile.toPath().relativize(
                                resolveLib(it).toPath()
                        ).joinToString("/")
                    })
            Files.move(
                    tmpJarFile.toPath(), outFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            )
            return finalMetaData
        }

        fun compileIfOutOfDate() = if (
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

        fun deploy(target: String) {
            val targetDir = File(target)
            val libDir = File(targetDir, "lib")
            val metaData = compileIfOutOfDate()
            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                System.exit(metaData.compilerExitCode)
            }
            debug("--> installing dependencies")
            val existing = when {
                libDir.exists() -> Files.newDirectoryStream(libDir.toPath())
                        .sorted()
                else -> emptyList()
            }
            var exidx = 0
            val ddep = metaData.dep.filter { d ->
                d.scope == Scope.Compile || d.scope == Scope.Runtime || (
                        d.scope == Scope.Compiler && d.id in
                                listOf("kotlin-stdlib", "kotlin-reflect")
                        )
            }.sortedBy { d -> "${d.id}-${d.version}${d.ext}" }.map { d ->
                val src = resolveLib(d)
                val name = when (val ext = src.name.lastIndexOf('.')) {
                    -1 -> src.name + "-" + d.sha256
                    else -> src.name.substring(0, ext) + "-" + d.sha256 +
                            src.name.substring(ext)
                }
                while (exidx < existing.size) {
                    val ex = existing[exidx]
                    if (ex.fileName.toString() > name) break
                    exidx++
                    if (ex.fileName.toString() < name) {
                        debug("- ${ex.fileName}")
                        Files.delete(ex)
                    }
                }
                val tgt = File(libDir, name)
                if (!tgt.exists()) {
                    debug("+ $name")
                    Files.createDirectories(libDir.toPath())
                    Files.copy(src.toPath(), tgt.toPath())
                }
                "lib/$name"
            }

            debug("--> installing main jar")
            val scriptName = scriptFile.name
            val outName = when (val ext = scriptName.lastIndexOf('.')) {
                -1 -> "$scriptName.jar"
                else -> scriptName.substring(0, ext) + ".jar"
            }
            val tgt = File(targetDir, "$outName~")
            Files.copy(outFile.toPath(), tgt.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            updateManifest(tgt, metaData.main, ddep)
            val permissions = PosixFilePermissions.asFileAttribute(setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ
            ))
            try {
                Files.setPosixFilePermissions(tgt.toPath(), permissions.value())
            } catch (_: UnsupportedOperationException) {
            }
            Files.move(tgt.toPath(), File(targetDir, outName).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
        }

        fun main(args: Array<String>) {
            val metaData = compileIfOutOfDate()

            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                System.exit(metaData.compilerExitCode)
            }

            val javaExe = File(javaHome, "bin${File.separator}java")
            val pb = ProcessBuilder(
                    javaExe.absolutePath,
                    "-Dkotlin_script.scriptFile.absolutePath=" +
                            scriptFile.absolutePath,
                    "-jar", outFile.absolutePath,
                    *args
            )
            pb.inheritIO()
            val p = pb.start()
            val rc = p.waitFor()
            System.exit(rc)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val javaHome = File(
                System.getProperty("java.home")
                        .removeSuffix("${File.separator}jre")
        )
        val userHome = File(System.getProperty("user.home")
                ?: error("user.home system property not set"))
        val cacheDir = File(userHome, ".kotlin_script")
        val localRepo = File(userHome, ".m2${File.separator}repository")
        val mavenRepoUrl = System.getProperty("maven.repo.url")
                ?: "https://repo1.maven.org/maven2"
        val mavenRepoCache = System.getProperty("maven.repo.cache")?.let(::File)

        val scriptArg = args.indexOfFirst { !it.startsWith("-") }
        if (scriptArg < 0) error("missing script filename")
        val scriptFileName = args[scriptArg]
        val scriptFile = File(scriptFileName).canonicalFile
        if (!scriptFile.exists()) error("file not found: '$scriptFile'")

        val flags = args.sliceArray(0 until scriptArg).map { arg ->
            val kv = arg.split('=', limit = 2)
            kv.first() to (kv.getOrNull(1) ?: "yes")
        }.toMap()

        val outFile = flags["--out"]?.let(::File) ?: File(
                cacheDir, "cache" + File.separator + scriptFile
                .absolutePath
                .absoluteToSubPath()
                + ".jar"
        )

        val ks = KotlinScript(
                javaHome = javaHome,
                kotlinScriptHome = cacheDir,
                mavenRepoUrl = mavenRepoUrl,
                mavenRepoCache = mavenRepoCache,
                localRepo = localRepo,
                scriptFile = scriptFile,
                outFile = outFile
        )

        val deploy = flags["--deploy"]
        if (deploy != null) {
            ks.deploy(deploy)
        } else {
            ks.main(args.sliceArray((scriptArg + 1) until args.size))
        }
    }
}
