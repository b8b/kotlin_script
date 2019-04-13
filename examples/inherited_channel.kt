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

///MAIN=Inherited_channelKt

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.spi.SelectorProvider

fun main() {
    val s = try {
        val pollSPC = Class.forName("sun.nio.ch.PollSelectorProvider")
        val pollSP = pollSPC.newInstance() as SelectorProvider
        pollSP.inheritedChannel() as ServerSocketChannel
    } catch (ex: Exception) {
        println("error initializing inherited socket: $ex")
        System.exit(1)
        return
    }
    val client = s.accept()
    client.write(ByteBuffer.wrap("hello world\n".toByteArray()))
    client.close()
    s.close()
}
