package com.callagent.rtcmedia

import java.io.DataInputStream
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** RFC 5389/5766 long-term credential check, used only for colliding TURN URLs.
 * Every authenticated Allocate has bounded, same-socket Refresh=0 cleanup,
 * even after a lost reply. Release is best effort, not guaranteed.
 * Diagnostics contain only phase/result/code, never credentials or URLs. */
object TurnCredentials {
    private val random=SecureRandom()
    private val watchdog=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"TURN-Deadline").apply{isDaemon=true} }
    private val workers=Executors.newFixedThreadPool(8) { r->Thread(r,"TURN-Credentials").apply{isDaemon=true} }
    internal fun attribute(type:Int,value:ByteArray):ByteArray = ByteBuffer.allocate(4+(value.size+3)/4*4).putShort(type.toShort()).putShort(value.size.toShort()).put(value).array()
    internal fun packet(type:Int,id:ByteArray,attributes:ByteArray,key:ByteArray?=null):ByteArray {
        val header=ByteBuffer.allocate(20).putShort(type.toShort()).putShort((attributes.size+if(key==null)0 else 24).toShort()).putInt(0x2112A442).put(id).array()
        val prefix=header+attributes
        return if(key==null)prefix else prefix+attribute(8,Mac.getInstance("HmacSHA1").apply{init(SecretKeySpec(key,"HmacSHA1"))}.doFinal(prefix))
    }
    internal fun attributes(data:ByteArray,id:ByteArray):Map<Int,ByteArray> {
        require(data.size>=20&&ByteBuffer.wrap(data,4,4).int==0x2112A442&&data.copyOfRange(8,20).contentEquals(id))
        val end=20+(ByteBuffer.wrap(data,2,2).short.toInt() and 65535);require(end==data.size)
        val result=linkedMapOf<Int,ByteArray>();var offset=20
        while(offset<end){require(offset+4<=end);val type=ByteBuffer.wrap(data,offset,2).short.toInt() and 65535;val size=ByteBuffer.wrap(data,offset+2,2).short.toInt() and 65535;require(offset+4+size<=end);result[type]=data.copyOfRange(offset+4,offset+4+size);offset+=4+(size+3)/4*4};require(offset==end);return result
    }
    private fun integrity(data:ByteArray,key:ByteArray):Boolean {
        var offset=20
        while(offset+4<=data.size){val type=ByteBuffer.wrap(data,offset,2).short.toInt() and 65535;val size=ByteBuffer.wrap(data,offset+2,2).short.toInt() and 65535
            if(offset+4+size>data.size)return false
            if(type==8){if(size!=20)return false;val prefix=data.copyOfRange(0,offset);ByteBuffer.wrap(prefix,2,2).putShort((offset+24-20).toShort());val expected=Mac.getInstance("HmacSHA1").apply{init(SecretKeySpec(key,"HmacSHA1"))}.doFinal(prefix);return MessageDigest.isEqual(expected,data.copyOfRange(offset+4,offset+24))}
            offset+=4+(size+3)/4*4
        };return false
    }
    private val cache=TurnProbeCache()
    private const val CLEANUP_MS=200L
    private fun errorCode(fields:Map<Int,ByteArray>):Int? = fields[9]?.takeIf{it.size>=4}?.let{(it[2].toInt() and 7)*100+(it[3].toInt() and 255)}
    private fun report(onEvent:(String)->Unit,phase:String,result:String,code:Int?=null) {
        // Logging must never skip allocation cleanup. No URLs or exception messages.
        runCatching{onEvent("turn_credential_probe phase=$phase result=$result"+(code?.let{" code=$it"}?:""))}
    }
    private fun cacheKey(url:String,user:String,password:String):String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(value in listOf(url,user,password)) {
            val bytes=value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun valid(url:String,server:RelayIceServer,timeoutMs:Int=350,onEvent:(String)->Unit={}):Boolean {
        val user=server.username;val password=server.credential
        if(user.isNullOrEmpty()||password.isNullOrEmpty()||timeoutMs<=0)return false
        return try {
            cache.check(cacheKey(url,user,password),timeoutMs.toLong()+CLEANUP_MS) {
                probe(url,user,password,timeoutMs,onEvent)
            }
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt();report(onEvent,"cache","interrupted");false
        } catch(_:Exception) { report(onEvent,"cache","wait_failed");false }
    }
    private fun probe(url:String,user:String,password:String,timeoutMs:Int,onEvent:(String)->Unit):Boolean {
        var phase="connect"
        var socket:Socket?=null
        var udp:DatagramSocket?=null
        var stop:ScheduledFuture<*>?=null
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        // Keep the same socket alive for a separate, bounded cleanup budget.
        val hardDeadline=deadline+TimeUnit.MILLISECONDS.toNanos(CLEANUP_MS)
        try {
            val scheme=url.substringBefore(':');require(scheme=="turn"||scheme=="turns")
            val uri=URI("$scheme://${url.substringAfter(':')}")
            val host=uri.host.removePrefix("[").removeSuffix("]")
            val port=if(uri.port>0)uri.port else if(scheme=="turns")5349 else 3478
            val tcp=scheme=="turns"||uri.rawQuery=="transport=tcp"
            socket=if(tcp){if(scheme=="turns")SSLSocketFactory.getDefault().createSocket() else Socket()}else null
            udp=if(tcp)null else DatagramSocket()
            val streamSocket=socket;val datagramSocket=udp
            stop=watchdog.schedule({runCatching{streamSocket?.close()};runCatching{datagramSocket?.close()}},maxOf(1,hardDeadline-System.nanoTime()),TimeUnit.NANOSECONDS)
            fun remaining(end:Long):Int {
                val nanos=end-System.nanoTime()
                if(nanos<=0)throw SocketTimeoutException()
                return TimeUnit.NANOSECONDS.toMillis(nanos).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
            }
            streamSocket?.apply {
                soTimeout=remaining(deadline);connect(InetSocketAddress(host,port),remaining(deadline))
                if(this is SSLSocket){sslParameters=sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};soTimeout=remaining(deadline);startHandshake()}
            }
            datagramSocket?.connect(InetSocketAddress(host,port))
            fun exchange(bytes:ByteArray,id:ByteArray,end:Long):ByteArray {
                remaining(end)
                if(streamSocket!=null)streamSocket.getOutputStream().write(bytes)
                else datagramSocket!!.send(DatagramPacket(bytes,bytes.size))
                while(true) {
                    val response=if(streamSocket!=null) {
                        val input=DataInputStream(streamSocket.getInputStream())
                        fun readFully(target:ByteArray) {
                            var offset=0
                            while(offset<target.size) {
                                streamSocket.soTimeout=remaining(end)
                                val count=input.read(target,offset,target.size-offset)
                                check(count>0);offset+=count
                            }
                        }
                        val header=ByteArray(20);readFully(header)
                        val size=ByteBuffer.wrap(header,2,2).short.toInt() and 65535;require(size<=8192)
                        val body=ByteArray(size);readFully(body);header+body
                    } else {
                        datagramSocket!!.soTimeout=remaining(end)
                        val reply=DatagramPacket(ByteArray(8212),8212);datagramSocket.receive(reply)
                        reply.data.copyOf(reply.length)
                    }
                    // A late Allocate response must not consume the Refresh reply.
                    if(response.size>=20&&response.copyOfRange(8,20).contentEquals(id))return response
                    remaining(end)
                }
            }
            phase="challenge"
            val id=ByteArray(12).also(random::nextBytes);val transport=attribute(0x19,byteArrayOf(17,0,0,0))
            val challenge=exchange(packet(3,id,transport),id,deadline);val fields=attributes(challenge,id)
            if(ByteBuffer.wrap(challenge,0,2).short.toInt()!=0x113||errorCode(fields)!=401) {
                report(onEvent,phase,"rejected",errorCode(fields));return false
            }
            val realm=fields[0x14]?:error("realm");val nonce=fields[0x15]?:error("nonce");require(realm.size<=1024&&nonce.size<=2048)
            val key=MessageDigest.getInstance("MD5").digest(user.toByteArray()+byteArrayOf(58)+realm+byteArrayOf(58)+password.toByteArray())
            val auth=attribute(6,user.toByteArray())+attribute(0x14,realm)+attribute(0x15,nonce)
            phase="allocate"
            try {
                val next=ByteArray(12).also(random::nextBytes)
                val allocated=exchange(packet(3,next,transport+auth,key),next,deadline)
                val allocatedFields=attributes(allocated,next)
                val ok=ByteBuffer.wrap(allocated,0,2).short.toInt()==0x103&&integrity(allocated,key)
                report(onEvent,phase,if(ok)"ok" else "rejected",errorCode(allocatedFields))
                return ok
            } finally {
                // Cancellation must not skip this bounded best effort. Restore the
                // interrupted flag afterwards; do not cancel a shared cache future.
                val interrupted=Thread.interrupted()
                try {
                    val cleanupDeadline=minOf(hardDeadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(CLEANUP_MS))
                    val release=ByteArray(12).also(random::nextBytes)
                    val response=exchange(packet(4,release,attribute(0x0d,ByteArray(4))+auth,key),release,cleanupDeadline)
                    val released=attributes(response,release)
                    val ok=ByteBuffer.wrap(response,0,2).short.toInt()==0x104&&integrity(response,key)&&released[0x0d]?.contentEquals(ByteArray(4))==true
                    report(onEvent,"release",if(ok)"ok" else "rejected",errorCode(released))
                } catch(_:SocketTimeoutException) { report(onEvent,"release","timeout") }
                  catch(_:Exception) { report(onEvent,"release","failed") }
                finally { if(interrupted)Thread.currentThread().interrupt() }
            }
        } catch(_:SocketTimeoutException) { report(onEvent,phase,"timeout");return false }
          catch(_:Exception) { report(onEvent,phase,"failed");return false }
        finally { stop?.cancel(false);runCatching{socket?.close()};runCatching{udp?.close()} }
    }

    /** One native credential per URL. Exact duplicates were already merged.
     * Only collisions perform network I/O, in parallel under a shared 1.2 s deadline.
     * Success/failure results are cached for 30/5 seconds with single-flight.
     * If no credential verifies, retain the first as an ordinary ICE fallback. */
    fun resolve(selection:RelaySelection,onEvent:(String)->Unit={},check:(String,RelayIceServer)->Boolean={url,server->valid(url,server,onEvent=onEvent)}):RelaySelection {
        val options=linkedMapOf<String,MutableList<RelayIceServer>>()
        selection.servers.forEach { s->s.urls.forEach{options.getOrPut(it){mutableListOf()}.add(s)} }
        val collisions=options.filterValues{it.map{s->s.username to s.credential}.distinct().size>1}
        if(collisions.isEmpty())return selection
        val deadline=System.nanoTime()+1_200_000_000L
        val attempts=collisions.mapValues{(url,items)->workers.submit<RelayIceServer>{items.firstOrNull{System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted&&check(url,it)}?:items.first()}}
        val selected=options.mapValues{(url,items)->attempts[url]?.let{runCatching{it.get(maxOf(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)}.getOrNull()}?:items.first()}
        attempts.values.forEach{it.cancel(true)}
        val servers=selection.servers.mapNotNull{s->val urls=s.urls.filter{selected[it]===s};if(urls.isEmpty())null else RelayIceServer(urls,s.username,s.credential)}
        return selection.withServers(servers)
    }
}
