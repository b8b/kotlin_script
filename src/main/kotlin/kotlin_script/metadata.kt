package kotlin_script

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class MetaData(
    val mainScript: Script,
    val kotlinScriptVersion: String,
    val main: String,
    val inc: List<Script>,
    val dep: List<Dependency>,
    val compilerArgs: List<String> = listOf(),
) {
    fun jarCachePath(jvmTarget: String? = null): Path {
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
        val jvmTargetInfo = if (jvmTarget == null) "" else "-java$jvmTarget"
        return ("sha256=" + input.sha256).let { checksum ->
            Paths.get(
                "org/cikit/kotlin_script_cache/$kotlinScriptVersion",
                "kotlin_script_cache-$kotlinScriptVersion-$jvmTargetInfo$checksum.jar"
            )
        }
    }

    private val String.sha256
        get() = MessageDigest.getInstance("SHA-256").let { md ->
            md.update(this.toByteArray())
            md.digest().joinToString("") { x -> String.format("%02x", x) }
        }

    private fun store(out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("///KOTLIN_SCRIPT_VERSION=$kotlinScriptVersion\n")
        w.write("///SCRIPT=${mainScript.path.fileName}\n")
        w.write("///CHK=${mainScript.checksum}\n")
        inc.forEach { s ->
            w.write("///INC=${s.path}\n")
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
        repo.resolve(
            "org/cikit/kotlin_script_cache/$kotlinScriptVersion" +
                    "/kotlin_script_cache-$kotlinScriptVersion" +
                    "-${mainScript.checksum}.metadata"
        )
    )

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

fun parseMetaData(kotlinScriptVersion: String, mainScript: Script): MetaData {
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
            ?: mainScript.path.fileName.toString().let { name ->
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
    )
}
