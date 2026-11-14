package com.callagent.rtcmedia

import okhttp3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*

/** Optional transport negotiated through capabilities. HTTP remains the recovery path. */
object NodeControlSocket {
    internal var clock:()->Long=System::nanoTime
    private fun now()=clock()
    private fun root(base:String)=base.trimEnd('/').removeSuffix("/v1")
    private val features=ConcurrentHashMap<String,Set<String>>()
    private data class Retry(var delay:Long=1000,var until:Long=0)
    private val retries=ConcurrentHashMap<String,Retry>()
    private val links=ConcurrentHashMap<String,Link>()
    private val listeners=ConcurrentHashMap<String,Pair<String,()->Unit>>()
    fun listen(base:String,owner:String,wake:()->Unit){listeners[owner]=root(base) to wake}
    fun unlisten(owner:String){listeners.remove(owner)}
    fun live(base:String,token:String)=links[root(base)]?.let{it.isLive()&&it.identity==identity(token)}==true
    private fun identity(token:String)=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun hint(base:String){listeners.values.filter{it.first==root(base)}.forEach{runCatching(it.second)}}
    fun observed(base:String,extensions:Set<String>){features[root(base)]=extensions}
    fun supports(base:String,extension:String)=features[root(base)]?.contains(extension)==true
    private val limits=ConcurrentHashMap<String,Long>()
    private fun critical(path:String,body:JSONObject?)=path=="/auth/revoke" || Regex("/rtc/control/calls/[A-Za-z0-9_-]+").matches(path) ||
        (Regex("/rtc/control/calls/[A-Za-z0-9_-]+/operations").matches(path)&&body?.optString("kind") in setOf("ended","declined","hangup","cancel")) ||
        (Regex("/rtc/legs/[A-Za-z0-9_-]+").matches(path)&&body?.optString("kind")=="close")
    fun limited(base:String,path:String,body:JSONObject?)=(limits[root(base)]?:0)>now()&&!critical(path.removePrefix("/v1"),body)
    fun rateLimited(base:String,seconds:Long){limits[root(base)]=now()+seconds.coerceIn(1,86400)*1_000_000_000L}
    private val client=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
        .pingInterval(25,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun request(base:String,token:String,method:String,path:String,body:JSONObject?,timeoutMs:Long=6000,idempotencyKey:String?=null):JSONObject? {
        if(limited(base,path,body))throw MediaApiException(429,"RATE_LIMITED")
        if(!supports(base,"rtc_control_ws_v1")||!path.matches(Regex("/(auth/(refresh|revoke)|gateways(/[A-Za-z0-9_-]+/(heartbeat|sims|sim-bindings|events:batch|commands(/[A-Za-z0-9_-]+/claim)?))?|clients|messages(/[A-Za-z0-9_-]+)?|events(/ack)?|devices/self/(rtc-capability|reception)|rtc/(control/(capability|calls(/[A-Za-z0-9_-]+(/operations)?)?|incoming(-policy)?)|legs/[A-Za-z0-9_-]+)|calls(/[A-Za-z0-9_-]+(/media(/active)?)?)?)(\\?[^#\\s]*)?")))return null
        val key=root(base);if((retries[key]?.until?:0)>now())return null
        val identity=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
        if(links.size>8){links.values.forEach{it.close()};links.clear()}
        val link=links.compute(key){_,old->if(old?.identity==identity)old else {old?.close();Link(key,token,identity)}}!!
        return try{link.request(method,path,body,timeoutMs.coerceIn(1,6000),idempotencyKey).also{if(link.stable())retries.remove(key)}}catch(failure:MediaApiException){
            if(failure.status in setOf(404,426)&&failure.code=="CONTROL_SOCKET_UNAVAILABLE")features.computeIfPresent(key){_,old->old-setOf("rtc_control_ws_v1","cold_standby_v1","client_ping_v1")}
            if(failure.status>=500 || (failure.status in setOf(404,426)&&failure.code=="CONTROL_SOCKET_UNAVAILABLE")){failed(key,link);null}
            else{if(failure.status==401){link.close();links.remove(key,link);if(path=="/auth/refresh")return null};throw failure}
        }catch(_:Exception){failed(key,link);null}
    }
    internal fun clearForTests(){links.values.forEach{it.close()};links.clear();features.clear();retries.clear();limits.clear();listeners.clear();clock=System::nanoTime}
    private fun failed(key:String,link:Link){
        link.close();links.remove(key,link)
        retries.compute(key){_,old->(old?:Retry()).apply{until=maxOf(until,now()+delay*1_000_000L);delay=(delay*2).coerceAtMost(300000)}}
    }
    private class Link(private val base:String,token:String,initialIdentity:String) {
        @Volatile var identity=initialIdentity;private set
        @Volatile private var live=false
        @Volatile private var snapshot:JSONObject?=null
        @Volatile private var snapshotAt=0L
        @Volatile private var lastLeaseReadAt=0L
        fun isLive()=live
        private val paths=ConcurrentHashMap<String,String>()
        private val idle=ConcurrentHashMap<String,JSONObject>()
        private val epoch=java.util.concurrent.atomic.AtomicLong()
        @Volatile private var openedAt=0L
        fun stable()=openedAt>0L&&now()-openedAt>=60_000_000_000L
        private val opened=CompletableFuture<Unit>()
        private val pending=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
        private val socket:WebSocket
        @Volatile private var blockedUntil=0L
        init{
            val url=(base+"/v1/rtc/control/ws").replaceFirst("https://","wss://").replaceFirst("http://","ws://")
            socket=client.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer $token").build(),object:WebSocketListener(){
                override fun onOpen(ws:WebSocket,response:Response){openedAt=now();live=true;opened.complete(Unit)}
                override fun onMessage(ws:WebSocket,text:String){
                    if(text.toByteArray().size>512*1024){close();return}
                    try{
                        val result=JSONObject(text);if(result.getInt("version")!=1){close();return}
                        if(result.optString("event")=="snapshot"){
                            epoch.incrementAndGet();idle.clear()
                            snapshot=JSONObject().put("version",1).put("calls",result.getJSONArray("calls")).put("poll_after_ms",750);snapshotAt=now();hint(base);return
                        }
                        val id=result.getString("request_id");val path=paths.remove(id)
                        if(path=="/auth/refresh"&&result.optInt("status")==200){
                            val next=result.getJSONObject("body").getString("access_token")
                            identity=MessageDigest.getInstance("SHA-256").digest(next.toByteArray()).joinToString(""){"%02x".format(it)}
                        }
                        if(path=="/rtc/control/calls"&&result.optInt("status")==200){snapshot=result.getJSONObject("body");snapshotAt=now();lastLeaseReadAt=now()}
                        pending.remove(id)?.complete(result)
                    }catch(_:Exception){close()}
                }
                override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){
                    live=false;hint(base)
                    val connectionLimit=response?.code==429&&runCatching{JSONObject(response.peekBody(8192).string()).optJSONObject("error")?.optString("code")=="CONTROL_CONNECTION_LIMIT"}.getOrDefault(false)
                    if(connectionLimit){val seconds=response?.header("Retry-After")?.toLongOrNull()?.coerceIn(1,86400)?:30L;retries.compute(root(base)){_,old->(old?:Retry()).apply{until=maxOf(until,now()+seconds*1_000_000_000L)}}}
                    else if(response?.code==429)rateLimited(base,response.header("Retry-After")?.toLongOrNull()?:60L)
                    val failure=MediaApiException(if(connectionLimit)503 else response?.code?:503,if(response?.code==401)"INVALID_CREDENTIALS" else "CONTROL_SOCKET_UNAVAILABLE")
                    opened.completeExceptionally(failure);pending.values.forEach{it.completeExceptionally(failure)};pending.clear()
                }
                override fun onClosing(ws:WebSocket,code:Int,reason:String){
                    live=false;hint(base);ws.close(if(code==1008)1008 else 1000,null)
                    val failure=MediaApiException(if(code==1008)401 else 503,if(code==1008)"INVALID_CREDENTIALS" else "CONTROL_SOCKET_UNAVAILABLE")
                    pending.values.forEach{it.completeExceptionally(failure)};pending.clear()
                }
                override fun onClosed(ws:WebSocket,code:Int,reason:String){live=false;onFailure(ws,IllegalStateException("closed"),null)}
            })
        }
        fun request(method:String,path:String,body:JSONObject?,timeout:Long,idempotencyKey:String?):JSONObject {
            if(method=="GET"&&live)idle[path]?.let{return JSONObject(it.toString())}
            if(method!="GET"){epoch.incrementAndGet();idle.clear()}
            val requestEpoch=epoch.get()
            if(path=="/rtc/control/calls"&&method=="GET"&&live){
                snapshot?.let { current->
                    val calls=current.optJSONArray("calls")?:org.json.JSONArray()
                    val active=(0 until calls.length()).any{calls.getJSONObject(it).optString("state") !in setOf("ended","unknown")}
                    if(!active||now()-lastLeaseReadAt<5_000_000_000L)return JSONObject(current.toString())
                }
            }
            if(now()<blockedUntil&&!critical(path,body))throw MediaApiException(429,"RATE_LIMITED")
            val until=now()+timeout*1_000_000L
            try{opened.get(timeout,TimeUnit.MILLISECONDS)}catch(failure:ExecutionException){throw failure.cause?:failure}
            val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();pending[id]=future;paths[id]=path
            try{
                val frame=JSONObject().put("version",1).put("request_id",id).put("method",method).put("path",path)
                if(body!=null)frame.put("body",body)
                idempotencyKey?.let{frame.put("idempotency_key",it)}
                check(socket.send(frame.toString()))
                val response=try{future.get(maxOf(1L,until-now()),TimeUnit.NANOSECONDS)}catch(failure:ExecutionException){throw failure.cause?:failure}
                val status=response.getInt("status");val payload=response.optJSONObject("body")?:JSONObject()
                if(status !in 200..299){
                    if(status==429){val seconds=response.optLong("retry_after",60);rateLimited(base,seconds);blockedUntil=now()+seconds.coerceIn(1,86400)*1_000_000_000L}
                    throw MediaApiException(status,payload.optJSONObject("error")?.optString("code")?:"CONTROL_ERROR")
                }
                if(method=="GET"&&live&&(path.startsWith("/events?")||path=="/events"||Regex("/gateways/[A-Za-z0-9_-]+/commands(\\?.*)?").matches(path))){
                    val values=payload.optJSONArray(if(path.startsWith("/events"))"items" else "commands")
                    if(values!=null&&values.length()==0&&epoch.get()==requestEpoch){if(idle.size>128)idle.clear();idle[path]=JSONObject(payload.toString())}
                }
                return payload
            }finally{pending.remove(id);paths.remove(id)}
        }
        fun close(){live=false;socket.cancel();opened.completeExceptionally(IllegalStateException("closed"));pending.values.forEach{it.completeExceptionally(IllegalStateException("closed"))};pending.clear()}
    }
}
