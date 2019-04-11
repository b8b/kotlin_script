package kotlin_script

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
                Scope.Plugin -> "PLUGIN"
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

    fun storeToFile(outFile: File) {
        val tmpFile = File(outFile.path + "~")
        Files.createDirectories(tmpFile.toPath().parent)
        FileOutputStream(tmpFile).use { out ->
            store(out)
        }
        Files.move(
                tmpFile.toPath(), outFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        )
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

fun parseMetaData(scriptFile: File): MetaData {
    val mainScript = loadScript(scriptFile.path)
    val metaDataMap = ByteArrayInputStream(mainScript.data).use { `in` ->
        `in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { line ->
            line.startsWith("///")
        }.map { line ->
            line.removePrefix("///").split('=', limit = 2)
        }.groupBy(
                keySelector = { pair -> pair.first() },
                valueTransform = { pair -> pair.getOrNull(1) ?: "" }
        )
    }
    val scripts = metaDataMap["INC"]?.map { s ->
        loadScript(s, scriptFile.parentFile)
    }
    val dep = listOf(
            "DEP" to Scope.Compile,
            "RDEP" to Scope.Runtime,
            "PLUGIN" to Scope.Plugin
    ).flatMap { scope ->
        metaDataMap[scope.first]?.map { spec ->
            parseDependency(spec).copy(scope = scope.second)
        } ?: emptyList()
    }
    return MetaData(
            metaDataMap["MAIN"]?.singleOrNull() ?: scriptFile.name?.let { name ->
                if (name.length > 3 && name.endsWith(".kt")) {
                    val trimmed = name.trim().removeSuffix(".kt")
                    trimmed.first().toUpperCase() +
                            trimmed.substring(1) +
                            "Kt"
                } else {
                    null
                }
            } ?: throw IllegalArgumentException("missing MAIN in meta data"),
            mainScript = mainScript,
            inc = scripts ?: emptyList(),
            dep = dep,
            compilerArgs = metaDataMap["CARG"] ?: emptyList(),
            compilerExitCode = metaDataMap["RC"]?.singleOrNull()?.toInt()
                    ?: 0,
            compilerErrors = metaDataMap["ERROR"] ?: emptyList()
    )
}
