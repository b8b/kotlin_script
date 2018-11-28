///MAIN=Access_metadataKt

object MetaData {
    operator fun invoke(): Map<String, List<String?>> =
        javaClass.getResourceAsStream("kotlin_script.metadata")?.use { `in` ->
            `in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { line ->
                line.startsWith("///")
            }.map { line ->
                line.removePrefix("///").split('=', limit  = 2)
            }.groupBy(
                keySelector = { pair -> pair.first().removePrefix("///") },
                valueTransform = { pair -> pair.getOrNull(1) ?: "" }
            )
        } ?: emptyMap()
}

fun main() {
    println(MetaData())
}
