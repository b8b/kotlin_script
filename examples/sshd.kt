#!/usr/bin/env kotlin_script

///MAIN=SshdKt

///DEP=org.apache.sshd:sshd-netty:2.1.0
///DEP=org.apache.sshd:sshd-core:2.1.0
///DEP=org.apache.sshd:sshd-common:2.1.0

///DEP=io.vertx:vertx-core:3.6.0

///DEP=io.netty:netty-common:4.1.30.Final
///DEP=io.netty:netty-buffer:4.1.30.Final
///DEP=io.netty:netty-transport:4.1.30.Final

///DEP=io.netty:netty-handler:4.1.30.Final
///DEP=io.netty:netty-handler-proxy:4.1.30.Final

///DEP=io.netty:netty-codec:4.1.30.Final
///DEP=io.netty:netty-codec-socks:4.1.30.Final
///DEP=io.netty:netty-codec-http:4.1.30.Final
///DEP=io.netty:netty-codec-http2:4.1.30.Final
///DEP=io.netty:netty-codec-dns:4.1.30.Final

///DEP=io.netty:netty-resolver:4.1.30.Final
///DEP=io.netty:netty-resolver-dns:4.1.30.Final

///DEP=org.slf4j:slf4j-api:1.7.25
///DEP=ch.qos.logback:logback-classic:1.2.3
///DEP=ch.qos.logback:logback-core:1.2.3

///DEP=com.fasterxml.jackson.core:jackson-databind:2.9.7
///DEP=com.fasterxml.jackson.core:jackson-annotations:2.9.0
///DEP=com.fasterxml.jackson.core:jackson-core:2.9.7

import io.vertx.core.Vertx
import org.apache.sshd.common.io.IoInputStream
import org.apache.sshd.common.io.IoOutputStream
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.netty.NettyIoServiceFactoryFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.StaticPublickeyAuthenticator
import org.apache.sshd.server.command.AsyncCommand
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.AbstractGeneratorHostKeyProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

fun main() {
    val vx = Vertx.factory.vertx()

    val sshServer = SshServer.setUpDefaultServer()
    sshServer.host = "localhost"
    sshServer.port = 2222
    sshServer.keyPairProvider = object : AbstractGeneratorHostKeyProvider() {
        override fun doWriteKeyPair(resourceKey: String?, kp: KeyPair?, outputStream: OutputStream?) {
        }

        override fun doReadKeyPair(resourceKey: String?, inputStream: InputStream?): KeyPair {
            throw IOException("not implemented")
        }
    }
    sshServer.passwordAuthenticator = null //StaticPasswordAuthenticator(true)
    sshServer.publickeyAuthenticator = object : StaticPublickeyAuthenticator(true) {}
    sshServer.ioServiceFactoryFactory = NettyIoServiceFactoryFactory(vx.nettyEventLoopGroup())
    sshServer.commandFactory = CommandFactory { command ->
        object : AsyncCommand {
            override fun setInputStream(`in`: InputStream?) {
            }

            override fun setErrorStream(err: OutputStream?) {
            }

            override fun setOutputStream(out: OutputStream?) {
            }

            override fun start(env: Environment?) {
            }

            override fun destroy() {
            }

            override fun setExitCallback(callback: ExitCallback) {
                callback.onExit(0)
            }

            override fun setIoOutputStream(out: IoOutputStream) {
                out.writePacket(ByteArrayBuffer("hello there, You requested command $command\n".toByteArray()))
                out.close(false).addListener { cf ->
                    println("closed: ${cf.isClosed}")
                }
            }

            override fun setIoInputStream(`in`: IoInputStream) {
                `in`.close()
            }

            override fun setIoErrorStream(err: IoOutputStream) {
                err.close()
            }
        }
    }
    sshServer.start()
}
