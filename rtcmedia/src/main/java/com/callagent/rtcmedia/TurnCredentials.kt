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
 * Allocate is authenticated; a successful allocation is immediately released.
 * Neither the challenge nor credentials are logged. All socket work has a hard deadline. */
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
    fun valid(url:String,server:RelayIceServer,timeoutMs:Int=350):Boolean=try {
        val user=server.username;val password=server.credential
        if(user.isNullOrEmpty()||password.isNullOrEmpty())false else {
            val scheme=url.substringBefore(':');val uri=URI("$scheme://${url.substringAfter(':')}");val host=uri.host.removePrefix("[").removeSuffix("]");val port=if(uri.port>0)uri.port else if(scheme=="turns")5349 else 3478
            val tcp=scheme=="turns"||uri.rawQuery=="transport=tcp"
            val socket=if(tcp){if(scheme=="turns")SSLSocketFactory.getDefault().createSocket() else Socket()}else null
            val udp=if(tcp)null else DatagramSocket()
            val stop=watchdog.schedule({runCatching{socket?.close()};runCatching{udp?.close()}},timeoutMs.toLong(),TimeUnit.MILLISECONDS)
            try {
                socket?.apply{soTimeout=timeoutMs;connect(InetSocketAddress(host,port),timeoutMs);if(this is SSLSocket){sslParameters=sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};startHandshake()}}
                udp?.apply{soTimeout=timeoutMs;connect(InetSocketAddress(host,port))}
                fun exchange(bytes:ByteArray):ByteArray {
                    if(socket!=null){socket.getOutputStream().write(bytes);val input=DataInputStream(socket.getInputStream());val header=ByteArray(20);input.readFully(header);val size=ByteBuffer.wrap(header,2,2).short.toInt() and 65535;require(size<=8192);val body=ByteArray(size);input.readFully(body);return header+body}
                    udp!!.send(DatagramPacket(bytes,bytes.size));val reply=DatagramPacket(ByteArray(8212),8212);udp.receive(reply);return reply.data.copyOf(reply.length)
                }
                val id=ByteArray(12).also(random::nextBytes);val transport=attribute(0x19,byteArrayOf(17,0,0,0))
                val challenge=exchange(packet(3,id,transport));val fields=attributes(challenge,id)
                require(ByteBuffer.wrap(challenge,0,2).short.toInt()==0x113)
                val error=fields[9]?:error("challenge");require(error.size>=4&&error[2].toInt()==4&&error[3].toInt()==1)
                val realm=fields[0x14]?:error("realm");val nonce=fields[0x15]?:error("nonce");require(realm.size<=1024&&nonce.size<=2048)
                val key=MessageDigest.getInstance("MD5").digest(user.toByteArray()+byteArrayOf(58)+realm+byteArrayOf(58)+password.toByteArray())
                val auth=attribute(6,user.toByteArray())+attribute(0x14,realm)+attribute(0x15,nonce)
                val next=ByteArray(12).also(random::nextBytes);val allocated=exchange(packet(3,next,transport+auth,key));attributes(allocated,next)
                val ok=ByteBuffer.wrap(allocated,0,2).short.toInt()==0x103&&integrity(allocated,key)
                if(ok)runCatching{val release=ByteArray(12).also(random::nextBytes);exchange(packet(4,release,attribute(0x0d,ByteArray(4))+auth,key))}
                ok
            }finally{stop.cancel(false);socket?.close();udp?.close()}
        }
    }catch(_:Exception){false}

    /** One native credential per URL. Exact duplicates were already merged.
     * Only collisions perform network I/O, in parallel under a shared 1.2 s deadline.
     * If no credential verifies, retain the first as an ordinary ICE fallback. */
    fun resolve(selection:RelaySelection,check:(String,RelayIceServer)->Boolean={url,server->valid(url,server)}):RelaySelection {
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
