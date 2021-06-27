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
v=1.4.10.1
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
kotlin_script="${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if [ -e "$kotlin_script" ]; then
  . "$kotlin_script"; exit 2
fi
fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
fetch_cmd="$(command -v wget) --no-check-certificate -qO" || \
fetch_cmd="curl -kfLSso"
dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
export K2_REPO="${K2_REPO:-https://repo1.maven.org/maven2}"
kotlin_script="$(mktemp)" || exit 1
if $fetch_cmd "$kotlin_script" "$K2_REPO"/"$p"; then
  case "$(${dgst_cmd} < "$kotlin_script")" in
  "d602c174f13855f8ec1bd14c50a3f7e5d29c7aa1e303e0aa4ea35186513849ea "*)
    . "$kotlin_script"; rm -f "$kotlin_script"; exit 2 ;;
  esac
  echo "error: failed to validate kotlin_script" >&2
else
  echo "error: failed to fetch kotlin_script" >&2
fi
rm -f "$kotlin_script"; exit 1
*/

fun main() {
    println("hello world!")
}
