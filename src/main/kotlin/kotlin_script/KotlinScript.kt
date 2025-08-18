package kotlin_script

import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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

    private val p = Progress(
        t = Terminal(),
        trace = trace,
        stdErrIsTerm = progress
    )

    private val resolver = Resolver(
        mavenRepoUrl = mavenRepoUrl,
        mavenRepoCache = mavenRepoCache,
        localRepo = localRepo,
        p = p
    )

    private fun defaultDependencyVersion(d: Dependency): String {
        return when (d.groupId) {
            KOTLIN_GROUP_ID -> KOTLIN_VERSION
            else -> error("no default version for $d")
        }
    }

    private fun kotlinCompilerArgs(
        compilerDependencies: List<Path>,
        compilerPlugins: List<Path>
    ): Array<String> {
        val cp = compilerDependencies.joinToString(File.pathSeparator) { f ->
            //TODO use correct quoting
            f.toAbsolutePath().toString()
        }
        return arrayOf(
            (javaHome / "bin" / "java").absolutePathString(),
            "-Djava.awt.headless=true",
            "-cp", cp,
            KOTLIN_JVM_COMPILER_MAIN,
            *compilerPlugins.map { p ->
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

        val compilerDependencies = mutableMapOf<Dependency, Path>()
        val resolvedDependencies = mutableMapOf<Dependency, Path>()

        val targetFile = jarCachePath(metaData)
        if (!force && targetFile.isReadable()) {
            // only need to fetch runtime dependencies
            resolver.resolveLibs(
                emptyList(),
                metaData.dep
                    .filterNot { d -> d.scope == Scope.Plugin }
                    .map { d ->
                        if (d.version.isBlank()) {
                            d.copy(version = defaultDependencyVersion(d))
                        } else {
                            d
                        }
                    },
                compilerDependencies,
                resolvedDependencies
            )
            return metaData
        }

        // copy script to temp dir
        val tmp = createTempDirectory(script.path.name)
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
                    nameCount
                )
                scriptSubPath
            }

            else -> script.path.fileName
        }
        val scriptTmpPath = tmp / scriptTmpSubPath
        val scriptTmpParent = scriptTmpPath.parent
        val incArgs = p.withProgress("initializing $tmp") {
            if (tmp != scriptTmpParent && !scriptTmpParent.exists()) {
                scriptTmpParent.createDirectories()
            }
            scriptTmpPath.outputStream().use { out ->
                out.write(metaData.mainScript.data)
            }

            // copy inc to temp dir
            metaData.inc.map { inc ->
                val tmpIncFile = scriptTmpParent / inc.path
                val tmpIncParent = tmpIncFile.parent
                if (tmp != tmpIncParent) {
                    tmpIncParent.createDirectories()
                }
                tmpIncFile.outputStream().use { out ->
                    out.write(inc.data)
                }
                inc.path.pathString
            }
        }

        // call compiler
        val (rc, compilerErrors) = if (scriptFileArgs.isNotEmpty()
                || incArgs.isNotEmpty()) {
            resolver.resolveLibs(
                compilerClassPath,
                metaData.dep.map { d ->
                    if (d.version.isBlank()) {
                        d.copy(version = defaultDependencyVersion(d))
                    } else {
                        d
                    }
                },
                compilerDependencies,
                resolvedDependencies
            )
            val kotlinCompilerArgs = kotlinCompilerArgs(
                compilerDependencies.map { (_, f) -> f.toAbsolutePath() },
                resolvedDependencies
                    .filter { (d, _) -> d.scope == Scope.Plugin }
                    .map { (_, f) -> f.toAbsolutePath() }
            )
            val compileClassPath = resolvedDependencies
                .filter { (d, _) -> d.scope == Scope.Compile }
                .map { (_, f) -> f.toAbsolutePath() }
            val compileClassPathArgs = when {
                compileClassPath.isEmpty() -> emptyList()
                else -> listOf(
                    "-cp",
                    compileClassPath.joinToString(File.pathSeparator)
                )
            }
            val compilerArgs: List<String> = listOf(
                *kotlinCompilerArgs,
                *metaData.compilerArgs.toTypedArray(),
                *compileClassPathArgs.toTypedArray(),
                "-d", tmp.toAbsolutePath().toString(),
                *scriptFileArgs.toTypedArray(),
                *incArgs.toTypedArray()
            )
            p.trace(*compilerArgs.toTypedArray())
            p.withProgress("compiling ${(scriptFileArgs + incArgs).first()}") {
                val compilerLog = scriptTmpParent / "kotlin_script.log"
                val compilerProcess = ProcessBuilder(compilerArgs)
                    .directory(scriptTmpParent.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(compilerLog.toFile())
                    .start()
                compilerProcess.outputStream.close()
                val rc = compilerProcess.waitFor()
                val compilerErrors = compilerLog.readText()
                rc to compilerErrors
            }
        } else {
            // only need to fetch runtime dependencies
            resolver.resolveLibs(
                emptyList(),
                metaData.dep
                    .filterNot { d -> d.scope == Scope.Plugin }
                    .map { d ->
                        if (d.version.isBlank()) {
                            d.copy(version = defaultDependencyVersion(d))
                        } else {
                            d
                        }
                    },
                compilerDependencies,
                resolvedDependencies
            )
            0 to ""
        }

        if (rc != 0) {
            System.err.println(compilerErrors)
            exitProcess(rc)
        }

        // embed metadata into jar
        p.trace("write", (tmp / "kotlin_script.metadata").absolutePathString())
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
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            targetFile.setPosixFilePermissions(permissions.value())
        }
        p.trace("write", targetFile.absolutePathString())
        targetFile.outputStream().use { out ->
            ZipOutputStream(out).use { zout ->
                zout.writeFileTree(tmp)
                zout.finish()
            }
        }

        cleanup(tmp)

        return metaData
    }

    companion object {
        private const val KOTLIN_VERSION = "2.2.10"
        private const val KOTLIN_GROUP_ID = "org.jetbrains.kotlin"

        private const val KOTLIN_SCRIPT_VERSION = "$KOTLIN_VERSION.29"

        private val kotlinStdlibDependency = Dependency(
            groupId = KOTLIN_GROUP_ID,
            artifactId = "kotlin-stdlib",
            version = KOTLIN_VERSION,
            sha256 = "9c67cc79efd6b9215b49d2a4308f5f3433537376c7c88e89bdd6729bd096e61a",
            size = 1750374
        )

        private val compilerClassPath = listOf(
            // BEGIN_COMPILER_CLASS_PATH
            kotlinStdlibDependency,
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-compiler-embeddable",
                version = KOTLIN_VERSION,
                sha256 = "79628fd5d64b4692a62f98962baab00e9076a37e2cffd6ace33cd0b7be7d1cdf",
                size = 56259401
            ),
            Dependency(
                groupId = "org.jetbrains.kotlin",
                artifactId = "kotlin-reflect",
                version = "1.6.10",
                sha256 = "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203",
                size = 3038560
            ),
            Dependency(
                groupId = "org.jetbrains",
                artifactId = "annotations",
                version = "13.0",
                sha256 = "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478",
                size = 17536
            ),
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-daemon-embeddable",
                version = KOTLIN_VERSION,
                sha256 = "b31573d418961fa080760fedcfc90d68ad67a85eafa245c73fe3d551476777ba",
                size = 345724
            ),
            Dependency(
                groupId = "org.jetbrains.kotlinx",
                artifactId = "kotlinx-coroutines-core-jvm",
                version = "1.8.0",
                sha256 = "9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5",
                size = 1548360
            ),
            Dependency(
                groupId = KOTLIN_GROUP_ID,
                artifactId = "kotlin-script-runtime",
                version = KOTLIN_VERSION,
                sha256 = "c304a67dba6a61dde560e6310920bed746307c3854f6675c29ac29114731b6b0",
                size = 44759
            ),
            // END_COMPILER_CLASS_PATH
        )

        private val userHome = Paths.get(System.getProperty("user.home")
            ?: error("user.home system property not set"))

        private val supportedJavaVersions = listOf(
            "1.8",
            *(9 .. 22).map(Int::toString).toTypedArray()
        )

        private const val KOTLIN_JS_COMPILER_MAIN =
            "org.jetbrains.kotlin.cli.js.K2JSCompiler"

        private const val KOTLIN_JVM_COMPILER_MAIN =
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"

        private fun cleanup(dir: Path) {
            if (!dir.exists()) {
                return
            }
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
