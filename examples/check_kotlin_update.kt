#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.61.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
repo=${repo:-https://repo1.maven.org/maven2}
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "0974fc19152728f27caaf2474d17957f05b791c87977cacb05722fcd586c72eb "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

import java.net.URL
import java.util.*
import kotlin.system.exitProcess

private const val tagsUrl = "https://api.github.com/repos/Jetbrains/kotlin/tags"
private val tagsRegex = """"name"\s*:\s*"v([0-9.]+)"""".toRegex()

val currentVersion = object {
    val vPattern = """.*/kotlin-stdlib-([0-9.]+)\.jar.*""".toRegex()
    val v = javaClass.classLoader
            .getResources("META-INF/MANIFEST.MF")
            .asSequence()
            .mapNotNull { url ->
                vPattern.matchEntire(url.toString())?.destructured?.component1()
            }.firstOrNull() ?: error("cannot detect current kotlin version")
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
            parts1[i].toInt().compareTo(parts2[i].toInt())
        }.first { it != 0 }
        if (result == 0) {
            parts1.size.compareTo(parts2.size)
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
        exitProcess(1)
    }
}
