#!/usr/bin/env kotlin_script

///MAIN=InstallerKt
///INC=KotlinScript.kt

import java.io.File
import java.io.FileWriter
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.Attributes
import java.util.jar.Manifest

val metaData = KotlinScript.MetaData(
        "KotlinScript",
        KotlinScript.Script("KotlinScript.kt"),
        emptyList(),
        """
///COMPILER=org.jetbrains.kotlin:kotlin-stdlib:1.3.11::sha256=5ce7a979fe6a9b43d7e6c450061119717fce54bc50b49a130cfbd2c065e83fec
///COMPILER=org.jetbrains.kotlin:kotlin-reflect:1.3.11::sha256=b8472ffb8319c8b53861effe6aa95b1521f1cfdaac3fc16033e35864f112496d
///COMPILER=org.jetbrains.kotlin:kotlin-compiler:1.3.11::sha256=fa764e0514a4c2f12d8140a1992569e33175e068d2f6df4d5ae531d7f66a0954
///COMPILER=org.jetbrains.kotlin:kotlin-script-runtime:1.3.11::sha256=e18b943373fc5cb8881f48ee1cbbe0ce2e9de01788dd35cef3e31b9793c335ae
""".trimIndent().split('\n').map { line ->
            KotlinScript.parseDependency(line.removePrefix("///COMPILER="))
        }
)

val kotlinVersion = metaData.dep.map { it.version }.toSet().single()

fun File.absoluteUnixPath(): String {
    val path = this.invariantSeparatorsPath
    return when {
        path.startsWith("/") -> path
        path[1] == ':' -> "/" + path.substring(0, 1) + path.substring(2)
        else -> error("invalid path: $path")
    }
}

object Installer {
    val javaHome = File(
            System.getProperty("java.home")
                    .removeSuffix("${File.separator}jre")
    )
    val userHome = File(System.getProperty("user.home")
            ?: error("user.home system property not set"))
    val ksHome = File(userHome, ".kotlin_script")
    val localRepo = File(userHome, ".m2${File.separator}repository")
    val mavenRepoUrl = System.getProperty("maven.repo.url")
            ?: "https://repo1.maven.org/maven2"
    val mavenRepoCache = System.getProperty("maven.repo.cache")?.let(::File)

    val destination = File(ksHome, "kotlin_script-$kotlinVersion.jar")
    val scriptFile = File(destination.path.removeSuffix(".jar") + ".kt")

    val binDir = File(ksHome, "bin")

    val kotlinCompilerDir = "kotlin-compiler-$kotlinVersion/kotlinc/lib"

    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = "KotlinScript"
        mainAttributes[Attributes.Name.CLASS_PATH] =
                "$kotlinCompilerDir/kotlin-stdlib.jar " +
                "$kotlinCompilerDir/kotlin-reflect.jar"
    }

    fun installLibs() {
        val ks = KotlinScript.KotlinScript(
                javaHome = javaHome,
                kotlinScriptHome = ksHome,
                mavenRepoUrl = mavenRepoUrl,
                mavenRepoCache = mavenRepoCache,
                localRepo = localRepo,
                scriptFile = scriptFile,
                outFile = destination
        )

        val libDir = File(ksHome, kotlinCompilerDir)
        Files.createDirectories(libDir.toPath())
        metaData.dep.forEach { dep ->
            val src = ks.resolveLib(dep)
            val tgt = "${dep.id}${dep.ext}"
            Files.copy(src.toPath(), File(libDir, tgt).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyClassFiles(to: FileSystem) {
        val url = javaClass.getResource("/InstallerKt.class")
        if (url.protocol != "jar") error("cannot derive location of " +
                "kotlin_script_installer.jar from $url")
        val env = mapOf<String, String>()
        val uri = URI.create("jar:" +
                url.path.removeSuffix("!/InstallerKt.class"))
        FileSystems.newFileSystem(uri, env).use { fs ->
            fs.rootDirectories.forEach { dir ->
                Files.list(dir).filter { f ->
                    f.fileName.toString().startsWith("KotlinScript")
                }.forEach { f ->
                    val toPath = to.getPath(f.fileName.toString())
                    Files.copy(f, toPath,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    fun installJar() {
        val uri = URI.create("jar:" + destination.toURI())
        val env = mapOf("create" to "true")
        FileSystems.newFileSystem(uri, env).use { fs ->
            Installer.copyClassFiles(fs)
            val mdPath = fs.getPath("kotlin_script.compiler.metadata")
            Files.newOutputStream(mdPath, StandardOpenOption.CREATE)
                    .use { out ->
                        metaData.store(out)
                    }
            val manifestPath = fs.getPath("META-INF/MANIFEST.MF")
            Files.createDirectories(manifestPath.parent)
            Files.newOutputStream(manifestPath, StandardOpenOption.CREATE)
                    .use { out ->
                        manifest.write(out)
                    }
        }
    }

    fun installBatchScript() {
        Files.createDirectories(binDir.toPath())
        val batScriptFile = File(binDir, "kotlin_script.bat")
        val batScriptFileTmp = File(batScriptFile.path + "~")
        FileWriter(batScriptFileTmp).use { w ->
            w.write(
                    "@echo off\n\"" +
                            File(javaHome, "bin${File.separator}java").absolutePath +
                            "\" -jar \"${destination.absolutePath}\" %*\n"
            )
        }
        Files.move(
                batScriptFileTmp.toPath(), batScriptFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        )
    }

    fun installShScript() {
        Files.createDirectories(binDir.toPath())
        val permissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        )
        val shScriptFile = File(binDir, "kotlin_script~")
        try {
            Files.createFile(
                    shScriptFile.toPath(),
                    PosixFilePermissions.asFileAttribute(permissions)
            )
        } catch (_: UnsupportedOperationException) {
        } catch (ex: FileAlreadyExistsException) {
            Files.setPosixFilePermissions(shScriptFile.toPath(), permissions)
        }
        FileWriter(shScriptFile).use { w ->
            w.write(
                    "#!/bin/sh\nexec \"" +
                            File(javaHome, "bin/java").absoluteUnixPath() +
                            "\" -jar \"${destination.absoluteUnixPath()}\" \"$@\"\n"
            )
        }
        Files.move(
                shScriptFile.toPath(),
                File(shScriptFile.absolutePath.removeSuffix("~")).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        )
    }
}

fun main() {
    Installer.apply {
        installLibs()
        installJar()
        installBatchScript()
        installShScript()
    }
}
