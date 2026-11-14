package com.callagent.rtcmedia

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** App-private encrypted configuration. Source tokens never appear in diagnostics. */
object RelayRepository {
    private const val PREFS = "rtc-relay-sources"
    private const val KEY = "rtc-relay-sources-v1"
    private val watchdog=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"Relay-Deadline").apply{isDaemon=true} }
    private val workers = Executors.newFixedThreadPool(8) { r -> Thread(r,"Relay-Prepare").apply { isDaemon=true } }
    private data class Cache(val identity:String,val source:RelaySource,val expires:Long)
    private val probes = ConcurrentHashMap<String,Pair<Long,Long?>>()
    private class Provider(val name:String,val identity:String,val fetch:()->JSONObject?)
    private val nodeProviders=ConcurrentHashMap<String,Provider>()
    fun registerNode(id:String,name:String,identity:String=id,fetch:()->JSONObject?){nodeProviders[id]=Provider(name,identity,fetch)}
    private val inFlight=ConcurrentHashMap<String,CompletableFuture<Pair<Boolean,RelaySource>?>>()
    @Synchronized private fun rememberNode(context:Context,id:String,name:String){
        val items=load(context);if((0 until items.length()).any{items.getJSONObject(it).getString("id")==id}||items.length()>=7)return
        items.put(JSONObject().put("id",id).put("name",name.take(80)).put("type","node").put("enabled",true));save(context,items)
    }
    fun retainNodes(ids:Set<String>){nodeProviders.keys.retainAll(ids);cache.keys.filter{it.startsWith("node-")&&it !in ids}.forEach(cache::remove)}
    private val revision=java.util.concurrent.atomic.AtomicLong()
    fun observeNode(context:Context,id:String,name:String){runCatching{rememberNode(context,id,name)}}
    fun nodePolicy(context:Context,id:String?):Pair<Boolean,Boolean> {
        if(id==null)return true to false
        val items=runCatching{load(context)}.getOrNull()?:return true to false
        val item=(0 until items.length()).map{items.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?:return true to false
        return item.optBoolean("enabled",true) to item.optBoolean("pinned")
    }
    private val cache = ConcurrentHashMap<String,Cache>()
    @Volatile private var account = ""
    private fun key():javax.crypto.SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY,null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(context:Context):JSONArray {
        val blob=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("config",null)?:return JSONArray()
        return try {
            val bytes=Base64.decode(blob,Base64.NO_WRAP);require(bytes.size>12)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            JSONArray(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        }catch(_:Exception){throw IllegalStateException("relay_store_unavailable")}
    }
    @Synchronized fun reset(context:Context) {
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(KEY);revision.incrementAndGet();cache.clear()
    }
    @Synchronized fun save(context:Context,items:JSONArray) {
        require(items.length()<=7){"source_budget"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val blob=Base64.encodeToString(cipher.iv+cipher.doFinal(items.toString().toByteArray()),Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("config",blob).commit()){"relay_store_unavailable"}
        revision.incrementAndGet();cache.clear()
    }
    fun parseLink(value:String):JSONObject {
        require(value.length<=4096){"invalid_relay_link"}
        val uri=try{URI(value)}catch(_:Exception){throw IllegalArgumentException("invalid_relay_link")}
        require(uri.scheme=="mahogsm"&&uri.host=="relay"&&uri.fragment==null&&uri.path.orEmpty().isEmpty()&&uri.rawUserInfo==null&&uri.port==-1){"invalid_relay_link"}
        val params=uri.rawQuery.orEmpty().split('&').map { part->
            require(part.contains('=')){"invalid_relay_link"}
            java.net.URLDecoder.decode(part.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(part.substringAfter('='),"UTF-8")
        }
        require(params.size==2&&params.map{it.first}.toSet()==setOf("url","token")){"invalid_relay_link"}
        val base=params.first{it.first=="url"}.second;validateBase(base)
        val token=params.first{it.first=="token"}.second
        require(token.length in 1..512&&token.none{it.isWhitespace()||it.isISOControl()}){"invalid_relay_token"}
        return JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("name",URI(base).host).put("type","external").put("url",base).put("token",token).put("enabled",true).put("pinned",false)
    }
    fun validateBase(value:String):URI {
        val uri=try{URI(value)}catch(_:Exception){throw IllegalArgumentException("invalid_relay_url")}
        require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&(uri.port==-1||uri.port in 1..65535)){"invalid_relay_url"}
        return uri
    }
    fun servers(items:JSONArray):List<RelayIceServer> = (0 until items.length()).map { index ->
        val item=items.getJSONObject(index);val urls=item.getJSONArray("urls")
        RelayIceServer((0 until urls.length()).map(urls::getString),if(item.has("username"))item.getString("username")else null,if(item.has("credential"))item.getString("credential")else null)
    }
    /** Called off the UI thread after admission, with one total 1.5 s refresh deadline. */
    fun resolve(context:Context,scope:String,currentNodeId:String?=null,nodeServers:List<RelayIceServer> = emptyList()):List<RelaySource> {
        synchronized(this){if(account!=scope){account=scope;cache.clear()}}
        val config=runCatching{load(context)}.getOrElse{return emptyList()}
        val configRevision=revision.get()
        nodeProviders.forEach{(id,entry)->if((0 until config.length()).none{config.getJSONObject(it).getString("id")==id}&&config.length()<7){
            config.put(JSONObject().put("id",id).put("name",entry.name).put("type","node").put("enabled",true))
        }}
        val completion=ExecutorCompletionService<Pair<Boolean,RelaySource>?>(workers)
        var pending=0
        val futures=mutableListOf<Future<Pair<Boolean,RelaySource>?>>()
        val ready=mutableListOf<Pair<Boolean,RelaySource>>()
        val deadline=System.nanoTime()+1_500_000_000L
        for(i in 0 until config.length()) {
            val item=config.getJSONObject(i);if(!item.optBoolean("enabled",true))continue
            val id=item.getString("id");if(id==currentNodeId)continue;val pinned=item.optBoolean("pinned")
            if(item.getString("type")=="static") {
                ready.add(pinned to RelaySource(id,servers(item.getJSONArray("ice_servers")),pinned=pinned))
            }else{
                val identity=scope+":"+item.optString("url",id)+":"+item.optString("token")+":"+nodeProviders[id]?.identity.orEmpty()
                val cached=cache[id]?.takeIf{it.identity==identity}
                if(cached!=null&&cached.expires-System.currentTimeMillis()>1_800_000L){ready.add(pinned to cached.source);continue}
                pending++
                futures.add(completion.submit(Callable {
                    val mine=CompletableFuture<Pair<Boolean,RelaySource>?>()
                    val flightKey=id+":"+identity+":"+configRevision
                    val shared=inFlight.putIfAbsent(flightKey,mine)
                    if(shared!=null)return@Callable runCatching{shared.get(maxOf(1L,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)}.getOrNull()
                    fun fetch():Pair<Boolean,RelaySource>? {

                    return try {
                        if(item.getString("type")=="node") {
                            val provider=nodeProviders[id]?:return null
                            val data=provider.fetch()?:return null
                            val expires=Instant.parse(data.getString("expires_at")).toEpochMilli();check(expires-System.currentTimeMillis()>30_000)
                            val source=RelaySource(id,servers(data.getJSONArray("ice_servers")),pinned=pinned,staticOnly=false)
                            RelaySources.select(emptyList(),listOf(source));rememberNode(context,id,provider.name)
                            if(account==scope&&revision.get()==configRevision)cache[id]=Cache(identity,source,expires)
                            return pinned to source
                        }
                        validateBase(item.getString("url"))
                        val base=item.getString("url").trimEnd('/');val path=if(base.endsWith("/v1"))"/rtc/ice-servers" else "/v1/rtc/ice-servers"
                        val connection=java.net.URL(base+path).openConnection() as HttpURLConnection
                        val disconnect=watchdog.schedule({connection.disconnect()},maxOf(1L,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)
                        try {
                            connection.connectTimeout=1200;connection.readTimeout=1200;connection.instanceFollowRedirects=false
                            connection.setRequestProperty("Authorization","Bearer "+item.getString("token"))
                            val status=connection.responseCode
                            if(status==401||status==403){cache.remove(id);return null}
                            check(status==200)
                            val data=connection.inputStream.use{stream->val bytes=stream.readNBytesCompat(65537);check(bytes.size<=65536);JSONObject(String(bytes,Charsets.UTF_8))}
                            val expires=Instant.parse(data.getString("expires_at")).toEpochMilli()
                            check(expires-System.currentTimeMillis()>30_000)
                            val source=RelaySource(id,servers(data.getJSONArray("ice_servers")),pinned=pinned,staticOnly=false)
                            RelaySources.select(emptyList(),listOf(source))
                            if(account==scope&&revision.get()==configRevision)cache[id]=Cache(identity,source,expires)
                            pinned to source
                        }finally{disconnect.cancel(false);connection.disconnect()}
                    }catch(failure:Exception){
                        if(failure is MediaApiException && failure.status in listOf(401,403)){cache.remove(id);null}
                        else cached?.takeIf{it.expires-System.currentTimeMillis()>30_000}?.let{pinned to it.source}
                    }
                    }
                    try{fetch().also{mine.complete(it)}}catch(failure:Throwable){mine.completeExceptionally(failure);throw failure}finally{inFlight.remove(flightKey,mine)}

                }))
            }
        }
        repeat(pending){
            val remaining=deadline-System.nanoTime();if(remaining<=0)return@repeat
            val result=completion.poll(remaining,TimeUnit.NANOSECONDS)?:return@repeat
            runCatching{result.get()}.getOrNull()?.let(ready::add)
        }
        futures.forEach{it.cancel(true)}
        val network=runCatching{context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork?.networkHandle}.getOrNull()
        val ranks=mutableMapOf<String,Long>()
        val probeResults=ExecutorCompletionService<Pair<String,Long?>>(workers)
        val enabledNode=if(nodePolicy(context,currentNodeId).first)nodeServers else emptyList()
        val probeCandidates=ready.map{it.second}+if(enabledNode.isEmpty())emptyList()else listOf(RelaySource(RelaySources.NODE_ID,enabledNode,staticOnly=false))
        val probeFutures=probeCandidates.map { source->probeResults.submit(Callable {
            val url=source.servers.flatMap{it.urls}.firstOrNull()?:return@Callable source.id to null
            val key="$network:$url";val old=probes[key]
            val rtt=if(old!=null&&System.nanoTime()-old.first<300_000_000_000L)old.second else RelayProbe.measure(url).also {
                if(probes.size>128)probes.clear();probes[key]=System.nanoTime() to it
            }
            source.id to rtt
        }) }
        repeat(probeFutures.size) {
            val remaining=deadline-System.nanoTime();if(remaining<=0)return@repeat
            val result=probeResults.poll(remaining,TimeUnit.NANOSECONDS)?:return@repeat
            runCatching{result.get()}.getOrNull()?.let{(id,rtt)->if(rtt!=null)ranks[id]=rtt}
        }
        probeFutures.forEach{it.cancel(true)}
        return ready.sortedWith(compareByDescending<Pair<Boolean,RelaySource>>{it.first}.thenBy{ranks[it.second.id]?:Long.MAX_VALUE}).map{(_,source)->RelaySource(source.id,source.servers,source.enabled,source.pinned,source.staticOnly,ranks[source.id])}
    }
    fun cachedRank(context:Context,servers:List<RelayIceServer>):Long? {
        val url=servers.flatMap{it.urls}.firstOrNull()?:return null
        val network=runCatching{context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork?.networkHandle}.getOrNull()
        return probes["$network:$url"]?.takeIf{System.nanoTime()-it.first<300_000_000_000L}?.second
    }
    private fun java.io.InputStream.readNBytesCompat(limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(output.size()<limit){val read=read(buffer,0,minOf(buffer.size,limit-output.size()));if(read<0)break;output.write(buffer,0,read)}
        return output.toByteArray()
    }
}
