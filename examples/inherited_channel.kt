#!/usr/bin/env kotlin_script

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
