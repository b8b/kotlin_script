#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
#
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.11.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  : ${repo:=https://repo1.maven.org/maven2}
  if which fetch >/dev/null 2>&1
  then fetch_cmd="fetch -aAqo"
  else fetch_cmd="curl -sSfo"
  fi
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  if ! $fetch_cmd "$local_repo"/"$artifact"~ "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    exit 1
  fi
  case "$(openssl dgst -sha256 -hex < "$local_repo"/"$artifact"~)" in
  *90ba683ba3819c6274e5fdb25513bc526bf8aba3d54736dee3bf0d1b7ac00a07*)
    mv -f "$local_repo"/"$artifact"~ "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

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
