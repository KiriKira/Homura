package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors

class TurnCredentialsTest {
    @Test fun expiredFirstCredentialFallsBackToAuthenticatedSecondOnSameUrl() {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { socket->
            socket.soTimeout=2000
            val worker=Executors.newSingleThreadExecutor()
            val task=worker.submit {
                repeat(5) {
                    val received=DatagramPacket(ByteArray(8192),8192);socket.receive(received)
                    val data=received.data.copyOf(received.length);val id=data.copyOfRange(8,20)
                    val fields=TurnCredentials.attributes(data,id)
                    val username=fields[6]?.toString(Charsets.UTF_8)
                    val type=ByteBuffer.wrap(data,0,2).short.toInt()
                    val response=when {
                        username==null->TurnCredentials.packet(0x113,id,TurnCredentials.attribute(9,byteArrayOf(0,0,4,1))+TurnCredentials.attribute(0x14,"realm".toByteArray())+TurnCredentials.attribute(0x15,"nonce".toByteArray()))
                        username=="bad"->TurnCredentials.packet(0x113,id,TurnCredentials.attribute(9,byteArrayOf(0,0,4,1)))
                        else->TurnCredentials.packet(if(type==4)0x104 else 0x103,id,TurnCredentials.attribute(0x0d,byteArrayOf(0,0,2,88)),MessageDigest.getInstance("MD5").digest("good:realm:secret".toByteArray()))
                    }
                    socket.send(DatagramPacket(response,response.size,received.socketAddress))
                }
            }
            try {
                val url="turn:127.0.0.1:${socket.localPort}?transport=udp"
                val selection=RelaySources.select(listOf(RelayIceServer(listOf(url),"bad","expired")),listOf(RelaySource("backup",listOf(RelayIceServer(listOf(url),"good","secret")))))
                val chosen=TurnCredentials.resolve(selection)
                assertEquals("good",chosen.servers.single().username)
                RelaySources.requireNativeCompatible(chosen)
                task.get(3,java.util.concurrent.TimeUnit.SECONDS)
            }finally{worker.shutdownNow()}
        }
    }
    @Test fun boundedFailedAttemptsPreserveOtherUrlsAndOneCredentialPerUrl() {
        val url="turn:shared:3478";val node=RelayIceServer(listOf(url,"turn:unique:3478"),"bad","p")
        val selection=RelaySources.select(listOf(node),listOf(RelaySource("other",listOf(RelayIceServer(listOf(url),"good","p")))))
        val chosen=TurnCredentials.resolve(selection){_,s->s.username=="good"}
        assertEquals(listOf("turn:unique:3478"),chosen.servers[0].urls)
        assertEquals("good",chosen.servers[1].username)
        RelaySources.requireNativeCompatible(chosen)
        assertEquals(2,TurnCredentials.resolve(selection){_,_->false}.servers.single().urls.size)
    }
}
