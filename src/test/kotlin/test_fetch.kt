#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
#
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
#
. build/libs/kotlin_script-*.sh
exit 2
*/

import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

private fun cleanup(dir: Path) {
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun postVisitDirectory(dir: Path, exc: IOException?) =
                FileVisitResult.CONTINUE.also { Files.delete(dir) }
        override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                FileVisitResult.CONTINUE.also { Files.delete(file) }
    })
}

fun main() {
    val buildDir = File("build")
    val baseDir = File(buildDir, "t_fetch")
    val localRepo = File(baseDir, "local_repo")
    val ksRepo = File(localRepo, "org/cikit/kotlin_script/kotlin_script")
    val ksHome = File(baseDir, "ks_home")
    val libsDir = File(buildDir, "libs")

    if (ksHome.exists()) cleanup(ksHome.toPath())
    Files.createDirectories(ksHome.toPath())

    if (localRepo.exists()) cleanup(localRepo.toPath())
    Files.newDirectoryStream(libsDir.toPath()).forEach { p ->
        val fileName = p.fileName.toString()
        val ext = fileName.substringAfterLast('.', "")
        if (fileName.startsWith("kotlin_script-") &&
                ext in listOf("sh", "jar")) {
            val v = fileName
                    .removePrefix("kotlin_script-")
                    .substringBeforeLast('.')
            Files.createDirectories(File(ksRepo, v).toPath())
            Files.copy(p, File(ksRepo, "$v/$fileName").toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
        }
    }

    val homeDir = File(System.getProperty("user.home"))
    val realLocalRepo = File(homeDir, ".m2/repository")
    val rc = ProcessBuilder(
            "/bin/sh", "-x", "./src/test/kotlin/examples/hello.kt")
            .also {
                val env = it.environment()
                env["ks_home"] = ksHome.toString()
                env["local_repo"] = localRepo.toString()
                env["repo"] = realLocalRepo.toURI().toString()
            }
            .inheritIO()
            .start()
            .waitFor()
    System.exit(rc)
}
