package kotlin_script

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.*

data class MetaData(
    val mainScript: Script,
    val kotlinScriptVersion: String,
    val main: String,
    val inc: List<Script>,
    val dep: List<Dependency>,
    val compilerArgs: List<String> = listOf(),
) {
    fun jarCachePath(jvmTarget: String): Path {
        val checksum = if (inc.isEmpty()) {
            mainScript.checksum
        } else {
            val input = buildString {
                append(mainScript.checksum)
                append(" ")
                append(mainScript.path.name)
                append("\n")
                for (script in inc) {
                    append(script.checksum)
                    append(" ")
                    append(script.path.pathString)
                    append("\n")
                }
            }
            "sha256=${input.sha256}"
        }
        return Path(
            "org/cikit/kotlin_script_cache",
            kotlinScriptVersion,
            "kotlin_script_cache-$kotlinScriptVersion-java$jvmTarget-$checksum.jar"
        )
    }

    private val String.sha256
        get() = MessageDigest.getInstance("SHA-256").let { md ->
            md.update(this.encodeToByteArray())
            md.digest().joinToString("") { x -> String.format("%02x", x) }
        }

    private fun store(out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("///KOTLIN_SCRIPT_VERSION=$kotlinScriptVersion\n")
        w.write("///SCRIPT=${mainScript.path.name}\n")
        w.write("///CHK=${mainScript.checksum}\n")
        inc.forEach { s ->
            w.write("///INC=${s.path.pathString}\n")
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
        w.flush()
    }

    fun storeToRepo(repo: Path) = storeToFile(
        repo / "org/cikit/kotlin_script_cache" / kotlinScriptVersion /
                "kotlin_script_cache-$kotlinScriptVersion-${mainScript.checksum}.metadata"
    )

    fun storeToFile(file: Path) {
        val tmpName = "${file.name}~"
        val tmp = file.parent
            ?.let { p -> p.createDirectories() / tmpName }
            ?: Path(tmpName)
        tmp.outputStream().use { out -> store(out) }
        tmp.moveTo(
            file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}

internal fun parseMetaData(
    kotlinScriptVersion: String,
    mainScript: Script
): MetaData {
    val metaDataMap = ByteArrayInputStream(mainScript.data).use { `in` ->
        `in`.bufferedReader().lineSequence().filter { line ->
            line.startsWith("///")
        }.map { line ->
            line.removePrefix("///").split('=', limit = 2)
        }.groupBy(
            keySelector = { pair -> pair.first() },
            valueTransform = { pair -> pair.getOrNull(1) ?: "" }
        )
    }
    val scriptFileParent = mainScript.path.toAbsolutePath().parent
    val scripts = metaDataMap["INC"]?.map { s ->
        loadScript(Path(s), scriptFileParent)
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
            ?: when (mainScript.path.extension) {
                "kt" -> mainScript.path.nameWithoutExtension
                    .replaceFirstChar { ch -> ch.uppercaseChar() }
                    .replace('.', '_')
                    .plus("Kt")
                else -> throw IllegalArgumentException(
                    "missing MAIN in meta data"
                )
            },
        mainScript = mainScript,
        inc = scripts ?: emptyList(),
        dep = dep,
        compilerArgs = metaDataMap["CARG"] ?: emptyList(),
    )
}
