package kotlin_script

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
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
    val size: Long? = null,
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
        get() = buildString {
            append(groupId.replace(".", "/"))
            append("/")
            append(artifactId)
            append("/")
            append(version)
            append("/")
            append(artifactId)
            append("-")
            append(version)
            classifier?.let { str ->
                append("-")
                append(str)
            }
            append(".")
            append(type)
        }
}

fun parseDependency(spec: String): Dependency {
    val parts = spec.split(':')
    if (parts.size == 2) {
        return Dependency(parts[0], parts[1], "")
    }
    if (parts.size < 3) {
        throw IllegalArgumentException("invalid dependency spec: $spec")
    }
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
    val path: Path,
    val checksum: String,
    val data: ByteArray
)

fun loadScript(file: Path, dir: Path? = null): Script {
    val inputFile = if (dir == null) file else dir.resolve(file)
    val md = MessageDigest.getInstance("SHA-256")
    val data = try {
        ByteArrayOutputStream().use { out ->
            Files.newInputStream(inputFile).use { `in` ->
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
    } catch (_: NoSuchFileException) {
        ByteArray(0)
    }
    val sha256 = md.digest().joinToString("") { String.format("%02x", it) }
    return Script(file, "sha256=$sha256", data)
}
