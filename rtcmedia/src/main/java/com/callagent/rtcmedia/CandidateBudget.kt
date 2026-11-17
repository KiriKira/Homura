package com.callagent.rtcmedia

/** Bounds trickled candidates independently of configured URL count.
 * Half remains available to host/reflexive paths even if TURN gathers first. */
internal class CandidateBudget {
    private val counts=mutableMapOf<String,Int>()
    @Synchronized fun accept(sdp:String):Boolean {
        val type=Regex("(?:^| )typ (host|srflx|prflx|relay)(?: |$)").find(sdp)?.groupValues?.get(1)?:return false
        val group=if(type=="prflx")"srflx" else type
        val maximum=if(group=="relay")64 else 32
        val old=counts[group]?:0;if(old>=maximum)return false
        counts[group]=old+1;return true
    }
}
