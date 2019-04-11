package kotlin_script

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest

enum class Scope {
    Plugin,
    Compile,
    Runtime
}

data class Dependency(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val classifier: String? = null,
        val type: String = "jar",
        val sha256: String? = null,
        val scope: Scope = Scope.Compile
) {

    fun toSpec(): String = "$groupId:$artifactId:$version" + when (classifier) {
        null -> ""
        else -> ":$classifier"
    } + when (type) {
        "jar" -> ""
        else -> "@$type"
    } + when (sha256) {
        null -> ""
        else -> ":sha256=$sha256"
    }

    val subPath: String
        get() = groupId.replace(".", "/") + "/" +
                artifactId + "/" + version + "/" +
                "${artifactId}-${version}" + when (classifier) {
            null -> ""
            else -> "-${classifier}"
        } + ".${type}"

}

fun parseDependency(spec: String): Dependency {
    val parts = spec.split(':')
    if (parts.size < 3)
        throw IllegalArgumentException("invalid dependency spec: $spec")
    val groupId = parts[0]
    val artifactId = parts[1]
    var type = "jar"
    val version = when (val i = parts[2].indexOf('@')) {
        in 0..Integer.MAX_VALUE -> parts[2].substring(0, i).also {
            type = parts[2].substring(i + 1)
        }
        else -> parts[2]
    }
    val classifier = when (val i = parts.getOrNull(3)?.indexOf('@')) {
        null -> ""
        in 0..Integer.MAX_VALUE -> parts[3].substring(0, i).also {
            type = parts[3].substring(i + 1)
        }
        else -> parts[3]
    }
    val checksum = parts.getOrNull(4)?.split('=', limit = 2)
    val sha256 = when (checksum?.firstOrNull()) {
        "sha256" -> checksum.getOrNull(1)?.trim()
        else -> null
    }
    val classifierOrNull = when (val trimmed = classifier.trim()) {
        "" -> null
        else -> trimmed
    }
    return Dependency(groupId, artifactId, version, classifierOrNull, type, sha256)
}

class Script(
        val path: String,
        val checksum: String,
        val data: ByteArray
) {
    fun cachePath(): String {
        val hex = checksum.substringAfter('=')
        return hex.substring(0, 2) + "/" + hex.substring(2)
    }
}

fun loadScript(f: String, chdir: File? = null): Script {
    val absoluteFile = if (chdir != null) {
        File(chdir, f)
    } else {
        File(f)
    }
    val md = MessageDigest.getInstance("SHA-256")
    val data = try {
        ByteArrayOutputStream().use { out ->
            FileInputStream(absoluteFile).use { `in` ->
                val buffer = ByteArray(1024 * 4)
                while (true) {
                    val read = `in`.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        }
    } catch (_: FileNotFoundException) {
        ByteArray(0)
    }
    val sha256 = md.digest().joinToString("") { String.format("%02x", it) }
    return Script(f, "sha256=$sha256", data)
}
