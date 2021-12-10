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
v=1.6.0.0
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
  "c7710288e71855c0ae05004fae38be70f7368f0432f6d660530205026e9bbfbd "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.apache.sshd:sshd-netty:2.8.0

///DEP=io.netty:netty-handler:4.1.69.Final
///DEP=io.netty:netty-buffer:4.1.69.Final
///DEP=io.netty:netty-codec:4.1.69.Final
///DEP=io.netty:netty-common:4.1.69.Final
///DEP=io.netty:netty-transport:4.1.69.Final
///DEP=io.netty:netty-resolver:4.1.69.Final

///DEP=org.apache.sshd:sshd-core:2.8.0
///DEP=org.apache.sshd:sshd-common:2.8.0

///DEP=org.bouncycastle:bcpkix-jdk15on:1.69
///DEP=org.bouncycastle:bcprov-jdk15on:1.69

///DEP=net.i2p.crypto:eddsa:0.3.0

///DEP=org.apache.sshd:sshd-git:2.8.0
///DEP=org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
///DEP=com.googlecode.javaewah:JavaEWAH:1.1.12

///DEP=org.slf4j:slf4j-api:1.7.32
///DEP=org.slf4j:jcl-over-slf4j:1.7.32
///RDEP=org.slf4j:slf4j-simple:1.7.32

///INC=pki.kt

import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.threads.SshThreadPoolExecutor
import org.apache.sshd.git.GitLocationResolver
import org.apache.sshd.git.pack.GitPackCommand
import org.apache.sshd.server.SshServer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

import net.i2p.crypto.eddsa.KeyPairGenerator as I2KeyPairGenerator

fun main() {
    //System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
    val log = LoggerFactory.getLogger("main")
    val repos = "/tmp/repos"
    val password = "changeit"
    val hostKeyPath = Paths.get("/tmp/host.key")

    val hostKp = if (Files.exists(hostKeyPath)) {
        OpenSSHKeyPairResourceParser().loadKeyPairs(
            null as SessionContext?,
            hostKeyPath,
            FilePasswordProvider.of(password)
        )
    } else {
        val kp = I2KeyPairGenerator().generateKeyPair()
        val options = OpenSSHKeyEncryptionContext()
        options.password = "changeit"
        options.cipherName = "aes"
        options.cipherType = "256"
        Files.newOutputStream(hostKeyPath).use { out ->
            OpenSSHKeyPairResourceWriter()
                .writePrivateKey(kp, "comment", options, out)
        }
        //TODO also generate alternative host keys
        listOf(kp)
    }

    val sshd = SshServer.setUpDefaultServer()
    sshd.port = 8022
    sshd.keyPairProvider = KeyPairProvider { session -> hostKp }
    sshd.setPublickeyAuthenticator { username, key, session ->
        log.info("authenticating $username with public-key auth ($session)")
        true
    }
    val gitLocationResolver = GitLocationResolver { command, args, session, fs ->
        log.info("resolving $command $args ($session) -> $repos")
        fs.getPath(repos)
    }
    val executorService = SshThreadPoolExecutor(
        2, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(100)
    )
    sshd.setCommandFactory { channel, command ->
        if (command.startsWith("git-upload-pack ")) {
            GitPackCommand(gitLocationResolver, command, executorService)
        } else {
            error("unsupported command: $command")
        }
    }

    sshd.start()
}
