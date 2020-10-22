package kotlin_script

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class MetaData(
        val kotlinScriptVersion: String,
        val main: String,
        val mainScript: Script,
        val inc: List<Script>,
        val dep: List<Dependency>,
        val localRepo: Path? = null,
        val compilerArgs: List<String> = listOf(),
        val compilerExitCode: Int = 0,
        val compilerErrors: List<String> = listOf()
) {
    val mdCachePath: Path
        get() = mainScript.checksum.let { checksum ->
            Paths.get(
                    "org/cikit/kotlin_script_cache/$kotlinScriptVersion",
                    "kotlin_script_cache-$kotlinScriptVersion-$checksum.metadata"
            )
        }

    val jarCachePath: Path
        get() {
            if (inc.isEmpty()) return mainScript.checksum.let { checksum ->
                Paths.get(
                        "org/cikit/kotlin_script_cache/$kotlinScriptVersion",
                        "kotlin_script_cache-$kotlinScriptVersion-$checksum.jar"
                )
            }
            val input = mainScript.checksum + " " + mainScript.path.fileName + "\n" +
                    inc.joinToString("") { script ->
                        script.checksum + " " + when (val baseDir = mainScript.path.parent) {
                            null -> script.path.fileName
                            else -> baseDir.relativize(script.path)
                        } + "\n"
                    }
            return ("sha256=" + input.sha256).let { checksum ->
                Paths.get(
                        "org/cikit/kotlin_script_cache/$kotlinScriptVersion",
                        "kotlin_script_cache-$kotlinScriptVersion-$checksum.jar"
                )
            }
        }

    private fun store(out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("///KOTLIN_SCRIPT_VERSION=$kotlinScriptVersion\n")
        w.write("///SCRIPT=${mainScript.path.fileName}\n")
        w.write("///CHK=${mainScript.checksum}\n")
        inc.forEach { s ->
            w.write("///INC=${s.path}\n")
            w.write("///CHK=${s.checksum}\n")
        }
        w.write("///MAIN=$main\n")
        dep.forEach { d ->
            val k = when (d.scope) {
                Scope.Plugin -> "PLUGIN"
                Scope.Runtime -> "RDEP"
                else -> "DEP"
            }
            w.write("///$k=${d.subPath}\n")
        }
        compilerArgs.forEach { w.write("///CARG=$it\n") }
        if (localRepo != null) {
            w.write("///REPO=$localRepo\n")
        }
        w.write("///MD_CACHE_PATH=$mdCachePath\n")
        w.write("///JAR_CACHE_PATH=$jarCachePath\n")
        w.write("///RC=$compilerExitCode\n")
        compilerErrors.forEach { w.write("///ERROR=$it\n") }
        w.flush()
    }

    fun storeToFile(file: Path) {
        val tmp = file.parent.resolve("${file.fileName}~")
        Files.createDirectories(tmp.parent)
        Files.newOutputStream(tmp).use { out ->
            store(out)
        }
        Files.move(
                tmp, file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        )
    }
}

fun parseMetaData(kotlinScriptVersion: String, scriptFile: Path): MetaData {
    val mainScript = loadScript(scriptFile)
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
    val scriptFileParent = scriptFile.toAbsolutePath().parent
    val scripts = metaDataMap["INC"]?.map { s ->
        loadScript(Paths.get(s), scriptFileParent)
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
            kotlinScriptVersion = kotlinScriptVersion,
            main = metaDataMap["MAIN"]?.singleOrNull()
                    ?: scriptFile.fileName.toString().let { name ->
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

val String.sha256: String
    get() {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(this.toByteArray())
        return md.digest().joinToString("") {
            String.format("%02x", it)
        }
    }
