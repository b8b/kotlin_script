package kotlin_script

import com.github.ajalt.mordant.animation.progressAnimation
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.system.exitProcess

class KotlinScript(
    private val javaHome: Path = findJavaHome(),
    private val mavenRepoUrl: String = System.getenv("M2_CENTRAL_REPO")
        ?.takeIf { v -> v.isNotBlank() }
        ?.trim()
        ?: "https://repo1.maven.org/maven2",
    private val mavenRepoCache: Path? = System.getenv("M2_LOCAL_MIRROR")
        ?.takeIf { v -> v.isNotBlank() }
        ?.trim()
        ?.let { Paths.get(it) },
    private val localRepo: Path = System.getenv("M2_LOCAL_REPO")
        ?.takeIf { v -> v.isNotBlank() }
        ?.trim()
        ?.let { Paths.get(it) }
        ?: userHome.resolve(".m2/repository"),
    private val progress: Boolean = false,
    private val trace: Boolean = false,
    private val force: Boolean = false,
) {
    private val javaVersion =
        System.getProperty("java.vm.specification.version") ?: "1.8"

    private val kotlinJvmTarget = when (javaVersion) {
        in supportedJavaVersions -> javaVersion
        else -> supportedJavaVersions.last()
    }

    private val terminal = if (progress) Terminal() else null

    private fun trace(vararg msg: String) {
        if (trace) {
            terminal?.let { t ->
                t.print(TextStyles.bold.invoke("++"), stderr = true)
                t.print(" ", stderr = true)
                msg.forEachIndexed { index, arg ->
                    val part = if (arg.startsWith("/")) {
                        if (":" in arg) {
                            val separator = TextStyles.bold.invoke(":")
                            arg.split(":").joinToString(separator) {
                                TextStyles.italic.invoke(it)
                            }
                        } else {
                            TextStyles.italic.invoke(arg)
                        }
                    } else if (arg.startsWith("-")) {
                        TextStyles.bold.invoke(arg)
                    } else {
                        arg
                    }
                    t.print(part, stderr = true)
                    if (index < msg.size - 1) {
                        t.print(" ", stderr = true)
                    }
                }
                t.println(stderr = true)
            } ?: System.err.println("++ ${msg.joinToString(" ")}")
        }
    }

    private fun defaultDependencyVersion(d: Dependency): String {
        return when (d.groupId) {
            KOTLIN_GROUP_ID -> KOTLIN_VERSION
            else -> error("no default version for $d")
        }
    }

    private fun copyFromRepoCache(dep: Dependency, tmp: Path): Boolean {
        if (mavenRepoCache == null) {
            return false
        }
        val source = mavenRepoCache / dep.subPath
        if (!source.isReadable()) {
            return false
        }
        val md = if (dep.sha256 != null) {
            MessageDigest.getInstance("SHA-256")
        } else {
            null
        }
        try {
            tmp.outputStream().use { out ->
                source.inputStream().use { `in` ->
                    val buffer = ByteArray(1024 * 4)
                    while (true) {
                        val n = `in`.read(buffer)
                        if (n < 0) break
                        md?.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        if (md != null) {
            val sha256 = md.digest().joinToString("") {
                String.format("%02x", it)
            }
            return dep.sha256 == sha256
        }
        return true
    }

    private fun fetchFromRepo(dep: Dependency, tmp: Path) {
        val md = if (dep.sha256 != null) {
            MessageDigest.getInstance("SHA-256")
        } else {
            null
        }
        val progressAnimation = terminal?.progressAnimation {
            spinner(Spinner.Lines())
            percentage()
            text("fetching dependencies")
            progressBar()
            timeRemaining()
        }
        try {
            tmp.outputStream().use { out ->
                val cn = URL("$mavenRepoUrl/${dep.subPath}").openConnection()
                progressAnimation?.updateTotal(cn.contentLengthLong)
                var totalWritten = 0L
                cn.inputStream.use { `in` ->
                    val buffer = ByteArray(1024 * 4)
                    while (true) {
                        val n = `in`.read(buffer)
                        if (n < 0) break
                        progressAnimation?.advance(n.toLong())
                        md?.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        totalWritten += n
                    }
                }
                if (totalWritten != cn.contentLengthLong) {
                    error(
                        "error fetching $dep: received $totalWritten Byte(s), " +
                                "expected ${cn.contentLengthLong} Byte(s)"
                    )
                }
            }
        } finally {
            progressAnimation?.clear()
        }
        if (md != null) {
            val sha256 = md.digest().joinToString("") {
                String.format("%02x", it)
            }
            if (dep.sha256 != sha256) {
                error("unexpected sha256=$sha256 for $dep")
            }
        }
    }

    private fun resolveLib(dep: Dependency): Path {
        val subPath = dep.subPath
        val f = localRepo / subPath
        if (f.exists()) return f
        f.parent?.createDirectories()
        val tmp = createTempFile(f.parent, "${f.name}~", "")
        try {
            if (copyFromRepoCache(dep, tmp)) {
                trace("cp $mavenRepoCache/$subPath $mavenRepoUrl/$subPath")
            } else {
                trace("fetch $mavenRepoUrl/$subPath")
                fetchFromRepo(dep, tmp)
            }
            tmp.moveTo(
                f,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            tmp.deleteIfExists()
        }
        return f
    }

    private fun kotlinCompilerArgs(plugins: List<Path>): Array<String> {
        val cp = compilerClassPath.joinToString(File.pathSeparator) { d ->
            val f = resolveLib(d)
            //TODO use correct quoting
            f.toAbsolutePath().toString()
        }
        return arrayOf(
            (javaHome / "bin" / "java").absolutePathString(),
            "-Djava.awt.headless=true",
            "-cp", cp,
            KOTLIN_COMPILER_MAIN,
            *plugins.map { p ->
                "-Xplugin=${p.absolutePathString()}"
            }.toTypedArray(),
            "-jvm-target", kotlinJvmTarget,
            "-no-reflect",
            "-no-stdlib"
        )
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
                        file.inputStream().use { `in` ->
                            `in`.copyTo(this@writeFileTree)
                        }
                        closeEntry()
                    }
                }
        })
    }

    fun jarCachePath(metaData: MetaData): Path =
        localRepo / metaData.jarCachePath(kotlinJvmTarget)

    fun compile(script: Script): MetaData {
        val scriptFileName = script.path.name
        val scriptFileArgs = when (script.path.extension) {
            "kt" -> listOf(scriptFileName)
            else -> emptyList()
        }
        val addClassPath = listOf(kotlinStdlibDependency)
        val metaData = parseMetaData(KOTLIN_SCRIPT_VERSION, script).let { md ->
            md.copy(dep = addClassPath + md.dep.map { d ->
                if (d.version.isBlank()) {
                    d.copy(version = defaultDependencyVersion(d))
                } else {
                    d
                }
            })
        }
        val compileClassPath = metaData.dep
            .filter { d -> d.scope == Scope.Compile }
            .map { d -> resolveLib(d).toAbsolutePath() }
        // resolve runtime dependencies
        metaData.dep
            .filter { d -> d.scope == Scope.Runtime }
            .forEach { d -> resolveLib(d) }
        val targetFile = jarCachePath(metaData)
        if (!force && targetFile.isReadable()) return metaData

        // copy script to temp dir
        val tmp = createTempDirectory(script.path.name)
        val copyProgress = terminal?.let { t ->
            t.progressAnimation {
                spinner(Spinner.Lines())
                text("initializing $tmp")
            }
        }
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            cleanup(tmp)
        })
        val maxDepth = metaData.inc.maxOfOrNull { inc ->
            // e.g. ../../../common/util.kt -> 2
            inc.path.indexOfLast { component -> component.name == ".." }
        } ?: -1
        val scriptTmpSubPath = when {
            maxDepth >= 0 -> {
                // e.g. maxDepth = 2
                // /work/kotlin_script/src/main/kotlin/main.kt
                // -> src/main/kotlin/main.kt
                val nameCount = script.path.nameCount
                val scriptSubPath = script.path.subpath(
                        nameCount - maxDepth - 2,
                        nameCount)
                scriptSubPath
            }
            else -> script.path.fileName
        }
        val scriptTmpPath = tmp / scriptTmpSubPath
        val scriptTmpParent = scriptTmpPath.parent
        if (tmp != scriptTmpParent) {
            scriptTmpParent.createDirectories()
            copyProgress?.advance(1)
        }
        scriptTmpPath.outputStream().use { out ->
            out.write(metaData.mainScript.data)
            copyProgress?.advance(1)
        }

        // copy inc to temp dir
        val incArgs = metaData.inc.map { inc ->
            val tmpIncFile = scriptTmpParent / inc.path
            val tmpIncParent = tmpIncFile.parent
            if (tmp != tmpIncParent) {
                tmpIncParent.createDirectories()
                copyProgress?.advance(1)
            }
            tmpIncFile.outputStream().use { out ->
                out.write(inc.data)
                copyProgress?.advance(1)
            }
            inc.path.pathString
        }

        copyProgress?.clear()

        // call compiler
        val compileClassPathArgs = when {
            compileClassPath.isEmpty() -> emptyList()
            else -> listOf(
                "-cp",
                compileClassPath.joinToString(File.pathSeparator)
            )
        }
        val plugins = metaData.dep.filter { d ->
            d.scope == Scope.Plugin
        }.map { d ->
            val addVersion = if (d.version.isBlank()) {
                d.copy(version = defaultDependencyVersion(d))
            } else {
                d
            }
            resolveLib(addVersion).toAbsolutePath()
        }
        val (rc, compilerErrors) = if (scriptFileArgs.isNotEmpty()
                || incArgs.isNotEmpty()) {
            val compilerArgs: List<String> = listOf(
                *kotlinCompilerArgs(plugins),
                *metaData.compilerArgs.toTypedArray(),
                *compileClassPathArgs.toTypedArray(),
                "-d", tmp.toAbsolutePath().toString(),
                *scriptFileArgs.toTypedArray(),
                *incArgs.toTypedArray()
            )
            trace(*compilerArgs.toTypedArray())
            val compilerProgress = terminal?.let { t ->
                val animation = t.progressAnimation {
                    spinner(Spinner.Lines())
                    text("compiling ${(scriptFileArgs + incArgs).first()}")
                }
                animation.updateTotal(Long.MAX_VALUE)
                animation
            }
            val compilerLog = scriptTmpParent / "kotlin_script.log"
            try {
                val compilerProcess = ProcessBuilder(
                    *compilerArgs.toTypedArray()
                )
                    .directory(scriptTmpParent.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(compilerLog.toFile())
                    .start()
                compilerProcess.outputStream.close()
                if (compilerProgress != null) {
                    while (!compilerProcess.waitFor(400, TimeUnit.MILLISECONDS)) {
                        compilerProgress.advance()
                    }
                }
                val rc = compilerProcess.waitFor()
                val compilerErrors = compilerLog.readText()
                rc to compilerErrors
            } finally {
                compilerProgress?.clear()
            }
        } else {
            0 to ""
        }

        if (rc != 0) {
            System.err.println(compilerErrors)
            exitProcess(rc)
        }

        // embed metadata into jar
        trace("write", (tmp / "kotlin_script.metadata").absolutePathString())
        metaData.storeToFile(tmp / "kotlin_script.metadata")
        val manifestFile = tmp.resolve(MANIFEST_PATH)
        val manifest = when {
            manifestFile.exists() ->
                manifestFile.inputStream().use { `in` ->
                    Manifest(`in`)
                }
            else -> Manifest()
        }
        manifest.mainAttributes.apply {
            Attributes.Name.MANIFEST_VERSION.let { key ->
                if (!contains(key)) put(key, "1.0")
            }
        }
        manifestFile.parent?.createDirectories()
        manifestFile.outputStream().use { out ->
            manifest.write(out)
        }

        targetFile.parent?.createDirectories()
        val permissions = PosixFilePermissions.asFileAttribute(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
        )
        try {
            Files.createFile(targetFile, permissions)
        } catch (_: UnsupportedOperationException) {
        } catch (ex: java.nio.file.FileAlreadyExistsException) {
            targetFile.setPosixFilePermissions(permissions.value())
        }
        trace("write", targetFile.absolutePathString())
        targetFile.outputStream().use { out ->
            ZipOutputStream(out).use { zout ->
                zout.writeFileTree(tmp)
                zout.finish()
            }
        }

        return metaData
    }

    companion object {
        private const val KOTLIN_VERSION = "1.9.21"
        private const val KOTLIN_GROUP_ID = "org.jetbrains.kotlin"

        private const val KOTLIN_SCRIPT_VERSION = "$KOTLIN_VERSION.22"

        private val kotlinStdlibDependency = Dependency(
            groupId = KOTLIN_GROUP_ID,
            artifactId = "kotlin-stdlib",
            version = KOTLIN_VERSION,
            sha256 = "3b479313ab6caea4e5e25d3dee8ca80c302c89ba73e1af4dafaa100f6ef9296a",
            size = 1718945
        )

        private val compilerClassPath = listOf(
            // BEGIN_COMPILER_CLASS_PATH
            kotlinStdlibDependency,
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-compiler-embeddable",
                version = KOTLIN_VERSION,
                sha256 = "46904b3d3f516560a48e0d93d9c7bfc63650b22d9f68f7a37eab5e5c5f3f785a",
                size = 60150107
            ),
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-script-runtime",
                version = KOTLIN_VERSION,
                sha256 = "1b1c74d476ffa41985b0b95dbe78ea5052061889f8106f1ae6cb5ee17f323f19",
                size = 43279
            ),
            Dependency(
                groupId = "org.jetbrains.kotlin",
                artifactId = "kotlin-reflect",
                version = "1.6.10",
                sha256 = "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203",
                size = 3038560
            ),
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-daemon-embeddable",
                version = KOTLIN_VERSION,
                sha256 = "01152ffb41b076e9c55083c513e1ef05f303cd5a95cedffcb89e124e340df11e",
                size = 398746
            ),
            Dependency(
                groupId = "org.jetbrains.intellij.deps",
                artifactId = "trove4j",
                version = "1.0.20200330",
                sha256 = "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d",
                size = 572985
            ),
            Dependency(
                groupId = "org.jetbrains",
                artifactId = "annotations",
                version = "13.0",
                sha256 = "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478",
                size = 17536
            ),
            // END_COMPILER_CLASS_PATH
        )

        private val userHome = Paths.get(System.getProperty("user.home")
            ?: error("user.home system property not set"))

        private val supportedJavaVersions = listOf(
            "1.8",
            *(9 .. 21).map(Int::toString).toTypedArray()
        )

        private const val KOTLIN_COMPILER_MAIN =
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"

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
            val javaHome = Path(System.getProperty("java.home"))
            return javaHome.parent?.takeIf { p ->
                // detekt jdk
                javaHome.endsWith("jre") && (p / "bin" / "java").isExecutable()
            } ?: javaHome
        }

        @JvmStatic
        @JvmName("compileScript")
        internal fun compileScript(
            scriptFile: Path,
            scriptData: ByteArray,
            scriptFileSha256: String,
            scriptMetadata: Path
        ): Path {
            val flags = System.getProperty("kotlin_script.flags") ?: ""
            val script = Script(scriptFile, "sha256=$scriptFileSha256", scriptData)
            val kotlinScript = KotlinScript(
                progress = "-P" in flags,
                trace = "-x" in flags,
                force = "-f" in flags,
            )
            val metaData = kotlinScript.compile(script)
            metaData.storeToFile(scriptMetadata)
            return kotlinScript.jarCachePath(metaData)
        }

    }
}
