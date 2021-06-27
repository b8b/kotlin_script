package kotlin_script

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class KotlinScript(
        val javaHome: Path,
        val kotlinScriptSh: Path?,
        val kotlinScriptJar: Path?,
        val kotlinStdlibJar: Path?,
        val mavenRepoUrl: String,
        val mavenRepoCache: Path?,
        val localRepo: Path,
        val progress: Boolean,
        val trace: Boolean,
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

    private val kotlinScriptVersion = manifest["Kotlin-Script-Version"]
            ?: error("no Kotlin-Script-Version in manifest")

    private val kotlinScriptDependency = Dependency(
            groupId = "org.cikit",
            artifactId = "kotlin_script",
            version = kotlinScriptVersion
    )

    private val kotlinStdlibDependency = compilerClassPath.single { dep ->
        dep.groupId == "org.jetbrains.kotlin" &&
                dep.artifactId == "kotlin-stdlib" &&
                dep.classifier == null &&
                dep.type == "jar"
    }.copy(scope = Scope.Compile)

    private fun copyIntoLocalRepo(src: Path, tgt: Path) {
        if (!Files.exists(tgt) && Files.exists(src)) {
            Files.createDirectories(tgt.parent)
            val tmp = Files.createTempFile(tgt.parent, "${tgt.fileName}~", "")
            try {
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp, tgt, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    private fun installKotlinScript() {
        if (kotlinScriptSh != null) {
            val src = kotlinScriptSh
            val tgt = localRepo.resolve(kotlinScriptDependency.copy(type = "sh").subPath)
            copyIntoLocalRepo(src, tgt)
        }
        if (kotlinScriptJar != null) {
            val src = kotlinScriptJar
            val tgt = localRepo.resolve(kotlinScriptDependency.subPath)
            copyIntoLocalRepo(src, tgt)
        }
    }

    private fun resolveLib(dep: Dependency): Path {
        val subPath = dep.subPath
        if (mavenRepoCache != null) {
            val f = mavenRepoCache.resolve(subPath)
            if (Files.exists(f)) return f
        }
        val f = localRepo.resolve(subPath)
        if (Files.exists(f)) return f
        Files.createDirectories(f.parent)
        val tmp = Files.createTempFile(f.parent, "${f.fileName}~", "")
        try {
            if (kotlinStdlibJar != null && dep.copy(scope = Scope.Compile) == kotlinStdlibDependency) {
                try {
                    Files.copy(kotlinStdlibJar, tmp, StandardCopyOption.REPLACE_EXISTING)
                    Files.move(
                            tmp, f,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    )
                    return f
                } catch (_: IOException) {
                }
            }

            when {
                trace -> System.err.println("++ fetch $mavenRepoUrl/$subPath")
                progress -> System.err.println("fetching $mavenRepoUrl/$subPath")
            }

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
                    tmp, f,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
        return f
    }

    private fun kotlinCompilerArgs(): Array<String> {
        val cp = compilerClassPath.joinToString(File.pathSeparator) { d ->
            val f = resolveLib(d)
            //TODO use correct quoting
            f.toAbsolutePath().toString()
        }
        return arrayOf(
                javaHome.resolve("bin/java").toAbsolutePath().toString(),
                "-Djava.awt.headless=true",
                "-cp", cp,
                kotlinCompilerMain,
                "-jvm-target",
                System.getProperty("java.vm.specification.version"),
                "-no-reflect",
                "-no-stdlib"
        )
    }

    fun compile(scriptFile: Path): MetaData {
        val metaData = parseMetaData(kotlinScriptVersion, scriptFile)

        // copy script to temp dir
        val tmp = Files.createTempDirectory(scriptFile.fileName.toString())
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            cleanup(tmp)
        })
        val maxDepth = metaData.inc.map { inc ->
            // e.g. ../../../common/util.kt -> 2
            inc.path.indexOfLast { component ->
                component.fileName.toString() == ".."
            }
        }.maxOrNull() ?: -1
        val scriptTmpSubPath = when {
            maxDepth >= 0 -> {
                // e.g. maxDepth = 2
                // /work/kotlin_script/src/main/kotlin/main.kt
                // -> src/main/kotlin/main.kt
                val nameCount = scriptFile.nameCount
                val scriptSubPath = scriptFile.subpath(
                        nameCount - maxDepth - 2,
                        nameCount)
                scriptSubPath
            }
            else -> scriptFile.fileName
        }
        val scriptTmpPath = tmp.resolve(scriptTmpSubPath)
        val scriptTmpParent = scriptTmpPath.parent
        if (tmp != scriptTmpParent) {
            Files.createDirectories(scriptTmpParent)
        }
        Files.newOutputStream(scriptTmpPath).use { out ->
            out.write(metaData.mainScript.data)
        }

        // copy inc to temp dir
        val incArgs = metaData.inc.map { inc ->
            val tmpIncFile = scriptTmpParent.resolve(inc.path)
            val tmpIncParent = tmpIncFile.parent
            if (tmp != tmpIncParent) {
                Files.createDirectories(tmpIncParent)
            }
            Files.newOutputStream(tmpIncFile).use { out ->
                out.write(inc.data)
            }
            inc.path.toString()
        }

        // call compiler
        val scriptFileName = scriptFile.fileName.toString()
        val scriptFileArgs = when (scriptFileName.endsWith(".kt")) {
            true -> listOf(scriptFileName)
            else -> emptyList()
        }
        val runtimeClassPath = compilerClassPath.filter {
            it.artifactId in listOf("kotlin-stdlib", "kotlin-reflect")
        }
        val compileClassPath = (
                runtimeClassPath + metaData.dep.filter { d ->
                    d.scope == Scope.Compile
                }).map { d -> resolveLib(d).toAbsolutePath() }
        val compileClassPathArgs = when {
            compileClassPath.isEmpty() -> emptyList()
            else -> listOf(
                    "-cp",
                    compileClassPath.joinToString(File.pathSeparator)
            )
        }
        val (rc, compilerErrors) = if (scriptFileArgs.isNotEmpty()
                || incArgs.isNotEmpty()) {
            val compilerArgs: List<String> = listOf(
                    *kotlinCompilerArgs(),
                    *compileClassPathArgs.toTypedArray(),
                    "-d", tmp.toAbsolutePath().toString(),
                    *scriptFileArgs.toTypedArray(),
                    *incArgs.toTypedArray()
            )
            if (trace) {
                System.err.println("++ ${compilerArgs.joinToString(" ")}")
            }
            val compilerProcess = ProcessBuilder(
                    *compilerArgs.toTypedArray())
                    .directory(scriptTmpParent.toFile())
                    .redirectErrorStream(true)
                    .start()
            compilerProcess.outputStream.close()
            val compilerErrors = compilerProcess.inputStream.use { `in` ->
                String(`in`.readBytes())
            }
            val rc = compilerProcess.waitFor()
            rc to compilerErrors
        } else {
            0 to ""
        }

        val finalMetaData = metaData.copy(
                dep = runtimeClassPath + metaData.dep,
                localRepo = localRepo,
                compilerExitCode = rc,
                compilerErrors = compilerErrors.split("\n")
        )
        finalMetaData.storeToFile(localRepo.resolve(metaData.mdCachePath))

        if (rc != 0) {
            return finalMetaData
        }

        val targetFile = localRepo.resolve(metaData.jarCachePath)

        // embed metadata into jar
        finalMetaData.storeToFile(
                tmp.resolve("kotlin_script.metadata")
        )
        val mainClass = finalMetaData.main
        val classPath = finalMetaData.dep.filter {
            it.scope in listOf(Scope.Compile, Scope.Runtime)
        }.map {
            val libFile = resolveLib(it)
            targetFile.toAbsolutePath().parent
                    .relativize(libFile.toAbsolutePath())
                    .joinToString("/")
        }
        val manifestFile = tmp.resolve(manifestPath)
        val manifest = when {
            Files.exists(manifestFile) ->
                Files.newInputStream(manifestFile).use { `in` ->
                    Manifest(`in`)
                }
            else -> Manifest()
        }
        manifest.mainAttributes.apply {
            Attributes.Name.MANIFEST_VERSION.let { key ->
                if (!contains(key)) put(key, "1.0")
            }
            Attributes.Name.MAIN_CLASS.let { key ->
                put(key, mainClass)
            }
            Attributes.Name.CLASS_PATH.let { key ->
                if (classPath.isNotEmpty()) {
                    put(key, classPath.joinToString(" "))
                }
                //TODO else remove class path attribute?
            }
        }
        Files.createDirectories(manifestFile.parent)
        Files.newOutputStream(manifestFile).use { out ->
            manifest.write(out)
        }

        Files.createDirectories(targetFile.parent)
        val permissions = PosixFilePermissions.asFileAttribute(setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ))
        try {
            Files.createFile(targetFile, permissions)
        } catch (_: UnsupportedOperationException) {
        } catch (ex: FileAlreadyExistsException) {
            Files.setPosixFilePermissions(targetFile, permissions.value())
        }
        Files.newOutputStream(targetFile).use { out ->
            ZipOutputStream(out).use { zout ->
                zout.writeFileTree(tmp)
                zout.finish()
            }
        }

        return finalMetaData
    }

    private fun ZipOutputStream.writeFileTree(start: Path) {
        val startFullPath = start.toUri().path
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    FileVisitResult.CONTINUE.also {
                        val fullPath = dir.toUri().path
                        val entryName = fullPath.removePrefix(startFullPath)
                        if (entryName.isNotEmpty()) {
                            putNextEntry(ZipEntry(entryName))
                            closeEntry()
                        }
                    }
            override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                    FileVisitResult.CONTINUE.also {
                        val fullPath = file.toUri().path
                        val entryName = fullPath.removePrefix(startFullPath)
                        if (entryName.isNotEmpty()) {
                            putNextEntry(ZipEntry(entryName))
                            Files.newInputStream(file).use { `in` ->
                                `in`.copyTo(this@writeFileTree)
                            }
                            closeEntry()
                        }
                    }
        })
    }

    companion object {

        private const val kotlinCompilerMain =
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        const val manifestPath = "META-INF/MANIFEST.MF"

        private fun cleanup(dir: Path) {
            try {
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun postVisitDirectory(dir: Path, exc: IOException?) =
                            FileVisitResult.CONTINUE.also { Files.delete(dir) }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                            FileVisitResult.CONTINUE.also { Files.delete(file) }
                })
            } catch (ex: Throwable) {
                System.err.println("warning: exception on cleanup: $ex")
            }
        }

        private fun findJavaHome(): Path {
            val javaHome = Paths.get(System.getProperty("java.home"))
            return if (javaHome.endsWith("jre") &&
                    Files.isExecutable(javaHome.parent.resolve("bin/java"))) {
                // detected jdk
                javaHome.parent
            } else {
                javaHome
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val userHome = Paths.get(System.getProperty("user.home")
                    ?: error("user.home system property not set"))

            val flags = mutableMapOf<String, String?>()
            var k = 0
            while (k < args.size) {
                val arg = args[k]
                if (!arg.startsWith("-")) break
                val key = when {
                    arg.startsWith("--") -> arg.substringBefore('=')
                    else -> arg
                }
                k++
                val v = when (key) {
                    "-d", "-M" -> args.getOrNull(k).also { k++ }
                    "--install-kotlin-script-sh",
                    "--install-kotlin-script-jar",
                    "--install-kotlin-stdlib-jar" -> when {
                        "=" in arg -> arg.substringAfter('=')
                        else -> args.getOrNull(k).also { k++ }
                    }
                    "-version", "-x", "-P" -> "yes"
                    else -> error("unknown option: $key")
                }
                flags[key] = v
            }

            val ks = KotlinScript(
                    javaHome = findJavaHome(),
                    kotlinScriptSh = flags["--install-kotlin-script-sh"]
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.let { Paths.get(it) },
                    kotlinScriptJar = flags["--install-kotlin-script-jar"]
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.let { Paths.get(it) },
                    kotlinStdlibJar = flags["--install-kotlin-stdlib-jar"]
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.let { Paths.get(it) },
                    mavenRepoUrl = System.getenv("M2_CENTRAL_REPO")
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.trim()
                            ?: "https://repo1.maven.org/maven2",
                    mavenRepoCache = System.getenv("M2_LOCAL_MIRROR")
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.trim()
                            ?.let { Paths.get(it) },
                    localRepo = System.getenv("M2_LOCAL_REPO")
                            ?.takeIf { v -> v.isNotBlank() }
                            ?.trim()
                            ?.let { Paths.get(it) }
                            ?: userHome.resolve(".m2/repository"),
                    progress = flags["-P"] == "yes",
                    trace = flags["-x"] == "yes"
            )

            if (flags["-version"] == "yes") {
                println(ks.kotlinScriptVersion)
                exitProcess(0)
            }

            val scriptFileName = args.getOrNull(k)
                    ?: error("missing script path")

            val scriptFile = Paths.get(scriptFileName)
            if (!Files.exists(scriptFile)) {
                error("file not found: '$scriptFile'")
            }

            ks.installKotlinScript()

            val metaData = ks.compile(scriptFile)
            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                exitProcess(metaData.compilerExitCode)
            }

            flags["-M"]?.let { storeMetaData ->
                metaData.storeToFile(Paths.get(storeMetaData))
            }

            flags["-d"]?.let { storeJar ->
                val target = Paths.get(storeJar)
                target.parent?.let { p -> Files.createDirectories(p) }
                Files.copy(ks.localRepo.resolve(metaData.jarCachePath), target,
                    StandardCopyOption.REPLACE_EXISTING)
            }
        }

    }
}
