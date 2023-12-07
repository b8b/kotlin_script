#!/bin/sh

/*/ __kotlin_script_installer__ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.9.21.19
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
url="${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"/"$p"
kotlin_script_sh="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if ! [ -r "$kotlin_script_sh" ]; then
  kotlin_script_sh="$(mktemp)" || exit 1
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"
  if ! $fetch_cmd "$kotlin_script_sh" "$url"; then
    echo "failed to fetch kotlin_script.sh from $url" >&2
    rm -f "$kotlin_script_sh"; exit 1
  fi
  dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
  case "$($dgst_cmd < "$kotlin_script_sh")" in
  "425beb05a5896b09ee916c5754e8262a837e15b4c40d6e9802f959b37210928e "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///PLUGIN=org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable

///DEP=org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.2
///DEP=org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.2

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.readBytes

@Serializable
private data class Sample(
    val id: String,
    val title: String,
    val q: Int
)

fun main(args: Array<String>) {
    println(Json.encodeToString(Sample("x126", "something", 4)))
    for (arg in args) {
        println("--> $arg")
        val data = Path(arg).readBytes()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data)
        println(md.digest().joinToString(", "))
        println(data.size)
    }
}
