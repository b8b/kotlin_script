package kotlin_script

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.Manifest

const val manifestPath = "META-INF/MANIFEST.MF"

fun debug(msg: String) = System.console()?.printf("%s\n", msg)

fun updateManifest(zipFile: File, mainClass: String, cp: List<String>) {
    val env = mapOf("create" to "false")
    val uri = URI.create("jar:" + zipFile.toURI())
    FileSystems.newFileSystem(uri, env).use { fs ->
        val nf = fs.getPath(manifestPath)
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