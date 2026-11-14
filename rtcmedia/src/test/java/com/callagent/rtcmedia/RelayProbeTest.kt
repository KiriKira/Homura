package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.DatagramPacket
import kotlin.concurrent.thread

class RelayProbeTest {
    @Test fun transactionMismatchAndTruncatedMessagesAreRejected() {
        val transaction=ByteArray(12){it.toByte()}
        val response=RelayProbe.request(transaction).also{it[0]=1;it[1]=1}
        assertTrue(RelayProbe.valid(response,transaction))
        assertFalse(RelayProbe.valid(response.copyOf(19),transaction))
        response[8]=99
        assertFalse(RelayProbe.valid(response,transaction))
    }
    @Test fun realUdpBindingResponseIsMeasuredAndLostReplyIsBounded() {
        DatagramSocket(0).use { server ->
            val responder=thread {
                val request=DatagramPacket(ByteArray(1024),1024);server.receive(request)
                val reply=request.data.copyOf(request.length).also{it[0]=1;it[1]=1}
                server.send(DatagramPacket(reply,reply.size,request.socketAddress))
            }
            assertNotNull(RelayProbe.measure("turn:127.0.0.1:${server.localPort}?transport=udp",200))
            responder.join()
            val start=System.nanoTime();assertNull(RelayProbe.measure("turn:127.0.0.1:${server.localPort}?transport=udp",50))
            assertTrue((System.nanoTime()-start)/1_000_000L<1000)
        }
    }
    @Test fun transportFailureDoesNotFabricateAnRtt() {
        assertNull(RelayProbe.measure("turns:127.0.0.1:1?transport=tcp",50))
    }
}
