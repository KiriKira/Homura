package com.callagent.rtcmedia

import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/** In-memory call diagnostics: never persists credentials or SDP. */
object RelayStatus {
    const val ACTION="com.callagent.rtcmedia.RELAY_CHANGED"
    private val labels=ConcurrentHashMap<String,Pair<Long,String>>()
    fun label(callId:String):String?=labels[callId]?.second
    fun latest():String?=labels.values.maxByOrNull{it.first}?.second
    internal fun clear(callId:String){labels.remove(callId)}
    internal fun record(context:Context,callId:String,sources:String){
        val config=runCatching{RelayRepository.load(context)}.getOrNull()
        val text=if(sources=="direct")"直连" else if(sources=="unknown")"中继来源待确认" else "中继："+sources.split(',').take(8).joinToString("、"){id->
            if(id==RelaySources.NODE_ID)"当前节点" else config?.let{items->(0 until items.length()).map{items.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?.optString("name")}?:"其他来源"
        }
        if(labels[callId]?.second==text)return
        if(labels.size>=32)labels.clear();labels[callId]=System.nanoTime() to text
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName).putExtra("call_id",callId))
    }
}
