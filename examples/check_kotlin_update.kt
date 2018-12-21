#!/usr/bin/env kotlin_script

///MAIN=Check_kotlin_updateKt

import java.net.URL
import java.util.*

private const val tagsUrl = "https://api.github.com/repos/Jetbrains/kotlin/tags"
private val tagsRegex = """"name"\s*:\s*"v([0-9.]+)"""".toRegex()

val currentVersion = object {
    val v = javaClass.getResourceAsStream("kotlin_script.metadata").use { input ->
        input.bufferedReader(Charsets.UTF_8).lineSequence().first { line ->
            line.startsWith("///COMPILER=org.jetbrains.kotlin:kotlin-stdlib:")
        }.substring(47).substringBefore(':')
    }
}.v

fun main() {
    println("getting tags from github")
    val json = URL(tagsUrl).openStream().use { input ->
        String(input.readBytes())
    }
    val tags = tagsRegex.findAll(json).map {
        it.destructured.component1()
    }
    val sorted = tags.sortedWith(Comparator { o1: String, o2: String ->
        val parts1 = o1.removePrefix("v").split('.')
        val parts2 = o2.removePrefix("v").split('.')
        val result = (0 until minOf(parts1.size, parts2.size)).map { i ->
            Integer.compare(parts1[i].toInt(), parts2[i].toInt())
        }.first { it != 0 }
        if (result == 0) {
            Integer.compare(parts1.size, parts2.size)
        } else {
            result
        }
    }).toList()
    sorted.forEach {
        if (it == currentVersion) {
            println("$it <-- current version")
        } else {
            println(it)
        }
    }
    if (sorted.last() != currentVersion) {
        println("--> new version found: ${sorted.last()}")
        System.exit(1)
    }
}
