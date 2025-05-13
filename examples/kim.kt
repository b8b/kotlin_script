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
v=2.0.0.24
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
  "741c518ddcabd1fb488e8c47f706eb74f5c00e59425ed87eb5e41f4593b835f3 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=com.ashampoo:kim-jvm:0.20
///DEP=io.ktor:ktor-io-jvm:3.0.0
///DEP=com.ashampoo:xmpcore-jvm:1.4.2
///DEP=org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.5.4
///DEP=org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.5.4

///DEP=com.github.ajalt.mordant:mordant-jvm:3.0.0
///DEP=com.github.ajalt.mordant:mordant-core-jvm:3.0.0
///DEP=com.github.ajalt.colormath:colormath-jvm:3.6.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-jna-jvm:3.0.0
///RDEP=net.java.dev.jna:jna:5.14.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.0

///DEP=com.github.ajalt.clikt:clikt-jvm:5.0.1
///DEP=com.github.ajalt.clikt:clikt-core-jvm:5.0.1

import com.ashampoo.kim.Kim
import com.ashampoo.kim.format.tiff.constant.ExifTag
import com.ashampoo.kim.input.JvmInputStreamByteReader
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

@OptIn(ExperimentalPathApi::class)
fun main(args: Array<String>) {
    for (arg in args) {
        val p = Path(arg)
        val files = if (p.isDirectory()) {
            p.walk()
        } else {
            sequenceOf(p)
        }
        for (file in files) {
            val md = FileChannel.open(file, StandardOpenOption.READ).use { fc ->
                val byteReader = JvmInputStreamByteReader(
                    Channels.newInputStream(fc),
                    fc.size()
                )
                Kim.readMetadata(byteReader)
            }
            println(
                md?.findStringValue(ExifTag.EXIF_TAG_DATE_TIME_ORIGINAL) + "\t$file"
            )
        }
    }
}
