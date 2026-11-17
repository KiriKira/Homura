package com.callagent.rtcmedia

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** STUN RTT is only an ordering hint. Failure never removes a TCP/TLS relay. */
object RelayProbe {
    private val random=SecureRandom()
    private val watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r->Thread(r,"Relay-Probe-Deadline").apply{isDaemon=true} }
    internal fun request(transaction:ByteArray):ByteArray {
        require(transaction.size==12)
        return ByteBuffer.allocate(20).putShort(1).putShort(0).putInt(0x2112A442).put(transaction).array()
    }
    internal fun valid(response:ByteArray,transaction:ByteArray):Boolean = response.size>=20 && response[0]==1.toByte() && response[1]==1.toByte() &&
        ByteBuffer.wrap(response,4,4).int==0x2112A442 && response.copyOfRange(8,20).contentEquals(transaction) && (ByteBuffer.wrap(response,2,2).short.toInt() and 0xffff)<=response.size-20
    fun measure(url:String,timeoutMs:Int=250):Long? = try {
        val scheme=url.substringBefore(':');val uri=URI("$scheme://${url.substringAfter(':')}")
        val host=uri.host.removePrefix("[").removeSuffix("]");val port=if(uri.port>0)uri.port else if(scheme=="turns")5349 else 3478
        val id=ByteArray(12).also(random::nextBytes);val packet=request(id);val started=System.nanoTime()
        val response=if(scheme=="turns"||uri.rawQuery=="transport=tcp") {
            val socket=if(scheme=="turns")SSLSocketFactory.getDefault().createSocket() else java.net.Socket()
            val stop=watchdog.schedule({runCatching{socket.close()}},timeoutMs.toLong(),java.util.concurrent.TimeUnit.MILLISECONDS)
            try { socket.use {
                it.soTimeout=timeoutMs;it.connect(InetSocketAddress(host,port),timeoutMs)
                if(it is SSLSocket){it.sslParameters=it.sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};it.startHandshake()}
                it.getOutputStream().write(packet)
                val header=ByteArray(20);java.io.DataInputStream(it.getInputStream()).readFully(header)
                val length=ByteBuffer.wrap(header,2,2).short.toInt() and 0xffff;require(length<=4096)
                val body=ByteArray(length);java.io.DataInputStream(it.getInputStream()).readFully(body);header+body
            } } finally {stop.cancel(false)}
        }else DatagramSocket().use {
            it.soTimeout=timeoutMs;it.connect(InetSocketAddress(host,port));it.send(DatagramPacket(packet,packet.size))
            val reply=DatagramPacket(ByteArray(4116),4116);it.receive(reply);reply.data.copyOf(reply.length)
        }
        if(valid(response,id))(System.nanoTime()-started)/1_000_000L else null
    }catch(_:Exception){null}
}
