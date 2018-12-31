#!/usr/bin/env kotlin_script

///MAIN=Access_metadataKt

val metaData = object {

    val metaData: Map<String, List<String>> =
        javaClass.getResourceAsStream("kotlin_script.metadata")?.use { `in` ->
            `in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { line ->
                line.startsWith("///")
            }.map { line ->
                line.removePrefix("///").split('=', limit = 2)
            }.groupBy(
                keySelector = { pair -> pair.first().removePrefix("///") },
                valueTransform = { pair -> pair.getOrNull(1) ?: "" }
            )
        } ?: emptyMap()

}.metaData

fun main() {
    metaData.forEach(::println)
}
