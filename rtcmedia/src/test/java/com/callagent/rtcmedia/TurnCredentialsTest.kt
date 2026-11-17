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
                repeat(6) {
                    val received=DatagramPacket(ByteArray(8192),8192);socket.receive(received)
                    val data=received.data.copyOf(received.length);val id=data.copyOfRange(8,20)
                    val fields=TurnCredentials.attributes(data,id)
                    val username=fields[6]?.toString(Charsets.UTF_8)
                    val type=ByteBuffer.wrap(data,0,2).short.toInt()
                    val response=when {
                        username==null->TurnCredentials.packet(0x113,id,TurnCredentials.attribute(9,byteArrayOf(0,0,4,1))+TurnCredentials.attribute(0x14,"realm".toByteArray())+TurnCredentials.attribute(0x15,"nonce".toByteArray()))
                        username=="bad"->TurnCredentials.packet(0x113,id,TurnCredentials.attribute(9,byteArrayOf(0,0,4,1)))
                        else->TurnCredentials.packet(if(type==4)0x104 else 0x103,id,TurnCredentials.attribute(0x0d,if(type==4)ByteArray(4) else byteArrayOf(0,0,2,88)),MessageDigest.getInstance("MD5").digest("good:realm:secret".toByteArray()))
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
                assertEquals("good",TurnCredentials.resolve(selection).servers.single().username)
                task.get(3,java.util.concurrent.TimeUnit.SECONDS)
            }finally{worker.shutdownNow()}
        }
    }
    private enum class ProbeReply { OK, DROP_ALLOCATE, MALFORMED_ALLOCATE, BAD_ALLOCATE_INTEGRITY, LATE_ALLOCATE, ALLOCATE_QUOTA, RELEASE_ERROR, BAD_RELEASE_INTEGRITY, DROP_RELEASE }

    private fun probeScenario(mode:ProbeReply):Pair<Boolean,List<String>> {
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        val username="probe_${mode.name}"
        val key=MessageDigest.getInstance("MD5").digest("$username:realm:secret".toByteArray())
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { socket ->
            socket.soTimeout=2000
            val worker=Executors.newSingleThreadExecutor()
            val task=worker.submit {
                fun receive():DatagramPacket=DatagramPacket(ByteArray(8192),8192).also(socket::receive)
                fun send(bytes:ByteArray,target:SocketAddress) { socket.send(DatagramPacket(bytes,bytes.size,target)) }
                val first=receive();val id=first.data.copyOfRange(8,20)
                send(TurnCredentials.packet(0x113,id,
                    TurnCredentials.attribute(9,byteArrayOf(0,0,4,1))+
                    TurnCredentials.attribute(0x14,"realm".toByteArray())+
                    TurnCredentials.attribute(0x15,"nonce".toByteArray())),first.socketAddress)
                val allocate=receive();val allocateId=allocate.data.copyOfRange(8,20)
                assertEquals(3,ByteBuffer.wrap(allocate.data,0,2).short.toInt())
                val success=TurnCredentials.packet(0x103,allocateId,TurnCredentials.attribute(0x0d,byteArrayOf(0,0,2,88)),key)
                when(mode) {
                    ProbeReply.DROP_ALLOCATE,ProbeReply.LATE_ALLOCATE -> Unit
                    ProbeReply.MALFORMED_ALLOCATE -> send(TurnCredentials.packet(0x103,allocateId,byteArrayOf(0)),allocate.socketAddress)
                    ProbeReply.BAD_ALLOCATE_INTEGRITY -> send(TurnCredentials.packet(0x103,allocateId,ByteArray(0),ByteArray(16)),allocate.socketAddress)
                    ProbeReply.ALLOCATE_QUOTA -> send(TurnCredentials.packet(0x113,allocateId,TurnCredentials.attribute(9,byteArrayOf(0,0,4,86)),key),allocate.socketAddress)
                    else -> send(success,allocate.socketAddress)
                }
                val release=receive()
                assertEquals("same socket/5-tuple must own cleanup",allocate.socketAddress,release.socketAddress)
                val data=release.data.copyOf(release.length);val releaseId=data.copyOfRange(8,20)
                val fields=TurnCredentials.attributes(data,releaseId)
                assertEquals(4,ByteBuffer.wrap(data,0,2).short.toInt())
                assertArrayEquals(ByteArray(4),fields[0x0d])
                val auth=TurnCredentials.attribute(6,username.toByteArray())+
                    TurnCredentials.attribute(0x14,"realm".toByteArray())+
                    TurnCredentials.attribute(0x15,"nonce".toByteArray())
                assertArrayEquals(TurnCredentials.packet(4,releaseId,TurnCredentials.attribute(0x0d,ByteArray(4))+auth,key),data)
                if(mode==ProbeReply.LATE_ALLOCATE)send(success,release.socketAddress)
                when(mode) {
                    ProbeReply.DROP_RELEASE -> Unit
                    ProbeReply.RELEASE_ERROR -> send(TurnCredentials.packet(0x114,releaseId,TurnCredentials.attribute(9,byteArrayOf(0,0,4,38)),key),release.socketAddress)
                    else -> send(TurnCredentials.packet(0x104,releaseId,TurnCredentials.attribute(0x0d,ByteArray(4)),if(mode==ProbeReply.BAD_RELEASE_INTEGRITY)ByteArray(16) else key),release.socketAddress)
                }
            }
            try {
                val url="turn:127.0.0.1:${socket.localPort}?transport=udp"
                val server=RelayIceServer(listOf(url),username,"secret")
                val result=TurnCredentials.valid(url,server,300){events.add(it)}
                task.get(3,java.util.concurrent.TimeUnit.SECONDS)
                // A second call with the exact credentials must not probe again.
                val beforeCacheRead=events.size
                assertEquals(result,TurnCredentials.valid(url,server,300){events.add(it)})
                assertEquals("cache hit must perform no new probe",beforeCacheRead,events.size)
                assertFalse(events.any{it.contains(username)||it.contains("secret")||it.contains("turn:")})
                return result to events.toList()
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun lostMalformedAndUnauthenticatedAllocateRepliesStillReleaseOnSameSocket() {
        for(mode in listOf(ProbeReply.DROP_ALLOCATE,ProbeReply.MALFORMED_ALLOCATE,ProbeReply.BAD_ALLOCATE_INTEGRITY,ProbeReply.LATE_ALLOCATE)) {
            val (valid,events)=probeScenario(mode)
            assertFalse(mode.name,valid)
            assertTrue(mode.name,events.any{it.contains("phase=release result=ok")})
        }
    }
    @Test fun successfulProbeVerifiesReleaseAndDoesNotHideReleaseErrors() {
        val (valid,events)=probeScenario(ProbeReply.OK)
        assertTrue(valid)
        assertTrue(events.any{it.contains("phase=release result=ok")})
        val (stillValid,rejected)=probeScenario(ProbeReply.RELEASE_ERROR)
        assertTrue(stillValid) // Credential validity and release outcome are separate.
        assertTrue(rejected.any{it.contains("phase=release result=rejected code=438")})
        val (_,badIntegrity)=probeScenario(ProbeReply.BAD_RELEASE_INTEGRITY)
        assertTrue(badIntegrity.any{it.contains("phase=release result=rejected")})
        val (_,timeout)=probeScenario(ProbeReply.DROP_RELEASE)
        assertTrue(timeout.any{it.contains("phase=release result=timeout")||it.contains("phase=release result=failed")})
    }
    @Test fun allocateQuotaErrorIsDiagnosedAndStillAttemptsCleanup() {
        val (valid,events)=probeScenario(ProbeReply.ALLOCATE_QUOTA)
        assertFalse(valid)
        assertTrue(events.any{it.contains("phase=allocate result=rejected code=486")})
        assertTrue(events.any{it.contains("phase=release result=ok")})
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
