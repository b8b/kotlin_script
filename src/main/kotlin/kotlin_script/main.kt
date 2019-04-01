package kotlin_script

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipOutputStream

private const val kotlinCompilerMain = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

class KotlinScript(
        val javaHome: File,
        val kotlinScriptHome: File,
        val mavenRepoUrl: String,
        val mavenRepoCache: File?,
        val localRepo: File
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
            ?.map { spec -> parseDependency(spec).copy(scope = Scope.Compiler) }
            ?: error("no compiler classpath in manifest")

    private fun resolveLib(dep: Dependency): File {
        val subPath = dep.groupId.replace(".", "/") + "/" +
                dep.artifactId + "/" + dep.version + "/" +
                "${dep.artifactId}-${dep.version}" + when (dep.classifier) {
            null -> ""
            else -> "-${dep.classifier}"
        } + ".${dep.type}"
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
        val sha256 = md.digest().joinToString("") {
            String.format("%02x", it)
        }
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

    private fun kotlinCompilerArgs(compilerDeps: List<Dependency>): Array<String> {
        val kotlinVersions = compilerDeps.map { d ->
            if (d.groupId == "org.jetbrains.kotlin" &&
                    d.artifactId == "kotlin-stdlib") {
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
            val copy = File(tmpKotlinHome, "lib${File.separator}${d.artifactId}.${d.type}")
            if (!copy.exists()) {
                Files.copy(f.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            //TODO use correct quoting
            copy.absolutePath
        }
        return arrayOf(
                File(javaHome, "bin${File.separator}java").absolutePath,
                "-Djava.awt.headless=true",
                "-cp", compilerClassPath,
                kotlinCompilerMain,
                "-kotlin-home", tmpKotlinHome.absolutePath
        )
    }

    fun compile(scriptFile: File, outFile: File): MetaData {
        val metaData = parseMetaData(scriptFile)

        val compilerDepsOverride = metaData.dep.filter { it.scope == Scope.Compiler }
        val rtDeps = compilerClassPath.filter {
            it.artifactId in listOf("kotlin-stdlib", "kotlin-reflect")
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
            File(scriptFile.parentFile, inc.path).path
        }
        val compilerArgs: List<String> = listOf(
                *kotlinCompilerArgs(compilerClassPath),
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
                dep = if (compilerDepsOverride.isEmpty()) {
                    compilerClassPath + metaData.dep
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
        finalMetaData.storeToZipFile(tmpJarFile, "kotlin_script.metadata")
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
            val mavenRepoCache = System.getProperty("maven.repo.cache")?.let(::File)

            val ks = KotlinScript(
                    javaHome = javaHome,
                    kotlinScriptHome = kotlinScriptHome,
                    mavenRepoUrl = mavenRepoUrl,
                    mavenRepoCache = mavenRepoCache,
                    localRepo = localRepo
            )

            val flags = mutableMapOf<String, String?>()
            var k = 0
            while (k < args.size) {
                val key = args[k]
                if (!key.startsWith("-")) break
                k++
                val v = when (key) {
                    "-d", "-M" -> args.getOrNull(k)
                    else -> error("unknown option: $k")
                }
                flags[key] = v
                if (v != null) k++
            }
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