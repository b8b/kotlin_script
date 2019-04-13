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
v=1.3.30.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" \
      "${repo:=https://repo1.maven.org/maven2}"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "ae510b3afffdf06c536d78730b0d4e29f161db0bf4c9e866f415747ae0294f28 "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

///MAIN=PublishKt

import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.*

private data class Item(val src: File, val targetName: String = src.name) {
    constructor(
            src: String,
            targetName: String = File(src).name
    ) : this(File(src), targetName)

    private fun digest(tgt: File, alg: String) {
        if (tgt.exists() && tgt.lastModified() > src.lastModified()) return
        val hex = FileInputStream(src).use { `in` ->
            val md = MessageDigest.getInstance(alg)
            md.update(`in`.readBytes())
            md.digest().joinToString("") {
                String.format("%02x", it)
            }
        }
        FileWriter(tgt).use { w ->
            w.write(hex)
            w.write("\n")
        }
    }

    private fun sign(tgt: File) {
        if (tgt.exists() && tgt.lastModified() > src.lastModified()) return
        try {
            Files.delete(tgt.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
        }
        val rc = ProcessBuilder()
                .command("gpg", "--detach-sign", "--armor", src.path)
                .inheritIO()
                .start()
                .waitFor()
        if (rc != 0) error("gpg2 terminated with exit code $rc")
    }

    fun upload(to: String, base64Credentials: String) {
        val md5 = File("${src.path}.md5")
        digest(md5, "MD5")
        val sha1 = File("${src.path}.sha1")
        digest(sha1, "SHA-1")
        val asc = File("${src.path}.asc")
        sign(asc)
        for (f in listOf(
                src to targetName,
                md5 to "$targetName.md5",
                sha1 to "$targetName.sha1",
                asc to "$targetName.asc"
        )) {
            val url = URL("$to/${f.second}")
            println("PUT $url")
            val cn = url.openConnection() as HttpURLConnection
            cn.requestMethod = "PUT"
            cn.doOutput = true
            cn.addRequestProperty("Authorization", "Basic $base64Credentials")
            cn.outputStream.use { out ->
                FileInputStream(f.first).use { `in` ->
                    `in`.copyTo(out)
                }
            }
            val responseBody = cn.inputStream.use { it.readBytes() }
            if (!cn.responseCode.toString().startsWith("2")) {
                error("failed with status ${cn.responseCode} " +
                        "${cn.responseMessage}\n" +
                        responseBody.toString(Charsets.UTF_8))
            }
        }
    }
}

fun main(args: Array<String>) {
    val v = args.singleOrNull()?.removePrefix("-v")
            ?: error("usage: publish.kt -v<version>")
    val user = System.console().readLine("Username: ")
    val pass = System.console().readPassword("Password: ")
    val base64Credentials = Base64.getUrlEncoder().encodeToString(
            "$user:${String(pass)}".toByteArray()
    )

    val repo = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
    val subDir = "org/cikit/kotlin_script/kotlin_script/$v"

    val libDir = "build/libs"

    val filesToPublish = listOf(
            Item(
                    "build/publications/mavenJava/pom-default.xml",
                    "kotlin_script-$v.pom"
            ),
            Item("$libDir/kotlin_script-$v.sh"),
            Item("$libDir/kotlin_script-$v.jar"),
            Item("$libDir/kotlin_script-$v-sources.jar"),
            Item("$libDir/kotlin_script-$v-javadoc.jar")
    )

    for (item in filesToPublish) {
        item.upload("$repo/$subDir", base64Credentials)
    }
}
