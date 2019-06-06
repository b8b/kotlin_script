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
v=1.3.31.1
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
  "d6cd4372ee10a2b2ff0db496a376be56823f0b7a6f36ccd9aaf8b9e7a26089b0 "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

import java.nio.file.Files
import java.io.*
import java.net.URL

private val homeDir = File(System.getProperty("user.home"))
private val githubUrl = "https://raw.githubusercontent.com"
private val baseUrl = "$githubUrl/udalov/kotlin-vim/master"

private fun download(subPath: String) {
    FileOutputStream(File(homeDir, ".vim/$subPath")).use { out ->
        URL("$baseUrl/$subPath").openStream().use { `in` ->
            `in`.copyTo(out)
        }
    }
}

fun main() {
    //0. mkdir -p ~/.vim/{syntax,indent,ftdetect,ftplugin}
    listOf("syntax", "indent", "ftdetect", "ftplugin", "syntax_checkers/kotlin")
            .forEach { subdir ->
                Files.createDirectories(File(homeDir, ".vim/$subdir").toPath())
            }

    //1. cp syntax/kotlin.vim ~/.vim/syntax/kotlin.vim
    download("syntax/kotlin.vim")

    //2. cp indent/kotlin.vim ~/.vim/indent/kotlin.vim
    download("indent/kotlin.vim")

    //3. cp ftdetect/kotlin.vim ~/.vim/ftdetect/kotlin.vim
    download("ftdetect/kotlin.vim")

    //4. cp ftplugin/kotlin.vim ~/.vim/ftplugin/kotlin.vim
    download("ftplugin/kotlin.vim")

    //5. If you use Syntastic: 
    //   cp -r syntax_checkers/kotlin ~/.vim/syntax_checkers/
    download("syntax_checkers/kotlin/kotlinc.vim")

    //6. Restart Vim
    println("restart vim to load kotlin support")
}
