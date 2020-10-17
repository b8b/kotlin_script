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
import kotlin.system.exitProcess

private const val kotlinCompilerMain =
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

const val manifestPath = "META-INF/MANIFEST.MF"

class KotlinScript(
        val javaHome: Path,
        val kotlinScriptHome: Path,
        val mavenRepoUrl: String,
        val mavenRepoCache: Path?,
        val localRepo: Path,
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
        val kotlinVersions = compilerClassPath.map { d ->
            if (d.groupId == "org.jetbrains.kotlin" &&
                    d.artifactId == "kotlin-stdlib") {
                d.version.trim()
            } else ""
        }.toSet()
        val kotlinVersion = kotlinVersions.singleOrNull(String::isNotEmpty)
                ?: error("conflicting kotlin versions in compiler dependencies")
        val tmpKotlinHome = kotlinScriptHome.resolve(
                "kotlin-compiler-$kotlinVersion/kotlinc"
        )
        val tmpKotlinLib = tmpKotlinHome.resolve("lib")
        Files.createDirectories(tmpKotlinLib)
        val cp = compilerClassPath.joinToString(File.pathSeparator) { d ->
            val f = resolveLib(d)
            val copy = tmpKotlinLib.resolve("${d.artifactId}.${d.type}")
            if (!Files.exists(copy)) {
                Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING)
            }
            //TODO use correct quoting
            copy.toAbsolutePath().toString()
        }
        return arrayOf(
                javaHome.resolve("bin/java").toAbsolutePath().toString(),
                "-Djava.awt.headless=true",
                "-cp", cp,
                kotlinCompilerMain,
                "-kotlin-home", tmpKotlinHome.toAbsolutePath().toString(),
                "-jvm-target",
                System.getProperty("java.vm.specification.version")
        )
    }

    fun compile(scriptFile: Path, outFile: Path): MetaData {
        val metaData = parseMetaData(scriptFile)

        // copy script to temp dir
        val tmp = Files.createTempDirectory(
                outFile.parent, outFile.fileName.toString()
                .removeSuffix(".jar") + "."
        )
        try {
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
                    println("++ ${compilerArgs.joinToString(" ")}")
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
                    compilerExitCode = rc,
                    compilerErrors = compilerErrors.split("\n")
            )
            if (rc == 0) {
                finalMetaData.storeToFile(
                        tmp.resolve("kotlin_script.metadata")
                )
                val mainClass = finalMetaData.main
                val classPath = finalMetaData.dep.filter {
                    it.scope in listOf(Scope.Compile, Scope.Runtime)
                }.map {
                    val libFile = resolveLib(it)
                    outFile.toAbsolutePath().parent
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

                Files.createDirectories(outFile.parent)
                val permissions = PosixFilePermissions.asFileAttribute(setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                ))
                try {
                    Files.createFile(outFile, permissions)
                } catch (_: UnsupportedOperationException) {
                } catch (ex: FileAlreadyExistsException) {
                    Files.setPosixFilePermissions(outFile, permissions.value())
                }
                Files.newOutputStream(outFile).use { out ->
                    ZipOutputStream(out).use { zout ->
                        zout.writeFileTree(tmp)
                        zout.finish()
                    }
                }
            }
            return finalMetaData
        } finally {
            cleanup(tmp)
        }
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

    companion object {

        private fun findJavaHome(): Path {
            val javaHome = Paths.get(System.getProperty("java.home"))
            return if (javaHome.endsWith("jre") &&
                    Files.isDirectory(javaHome.parent.resolve("bin"))) {
                // detected jdk
                javaHome.parent
            } else {
                javaHome
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val javaHome = findJavaHome()
            val userHome = Paths.get(System.getProperty("user.home")
                    ?: error("user.home system property not set"))
            val kotlinScriptHome = System.getProperty("kotlin_script.home")
                    ?.let { Paths.get(it) }
                    ?: userHome.resolve(".kotlin_script")
            val localRepo = System.getProperty("maven.repo.local")
                    ?.let { Paths.get(it) }
                    ?: userHome.resolve(".m2${File.separator}repository")
            val mavenRepoUrl = System.getProperty("maven.repo.url")
                    ?: "https://repo1.maven.org/maven2"
            val mavenRepoCache = System.getProperty("maven.repo.cache")
                    ?.let { Paths.get(it) }

            val flags = mutableMapOf<String, String?>()
            var k = 0
            while (k < args.size) {
                val key = args[k]
                if (!key.startsWith("-")) break
                k++
                val v = when (key) {
                    "-c", "-d", "-M" -> args.getOrNull(k).also { k++ }
                    "-version", "-x", "-P" -> "yes"
                    else -> error("unknown option: $key")
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

            val scriptFile = Paths.get(scriptFileName)
            if (!Files.exists(scriptFile)) {
                error("file not found: '$scriptFile'")
            }

            val target = flags["-d"] ?: error("missing -d option")
            val targetFile = Paths.get(target)
            val metaData = ks.compile(scriptFile, targetFile)

            when (val storeMetaData = flags["-M"]) {
                null -> {}
                else -> metaData.storeToFile(Paths.get(storeMetaData))
            }

            if (metaData.compilerExitCode != 0) {
                metaData.compilerErrors.forEach(System.err::println)
                exitProcess(metaData.compilerExitCode)
            }
        }

    }
}
