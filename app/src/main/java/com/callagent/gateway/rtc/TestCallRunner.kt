package com.callagent.gateway.rtc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.sim.SimRegistry
import com.callagent.rtcmedia.MediaApiException
import com.callagent.rtcmedia.PcmBridge
import com.callagent.rtcmedia.RelayRepository
import com.callagent.rtcmedia.RtcAudioSession
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers a host's test-call request with a virtual incoming call: no Telecom
 * and no cellular call, only the server's ordinary incoming flow and WebRTC
 * media fed with [TestCallAudio] in real time. One test at a time, only while
 * the gateway is idle; a real cellular call preempts it ([preempt]).
 *
 * Every stage is logged with its elapsed time from the request, so a test in
 * a given network can be compared with the media `quality` lines of the same
 * call_id on both phones.
 */
class TestCallRunner(
    private val context: Context,
    private val idle: () -> Boolean,
    private val log: (String) -> Unit
) : AutoCloseable {
    private class Active(val node: GatewayCallControl.Node, val requestId: String, val callId: String) {
        val startedAt: Long = SystemClock.elapsedRealtime()
        @Volatile var snapshot: JSONObject? = null
        @Volatile var media: RtcAudioSession? = null
        @Volatile var feeder: ScheduledFuture<*>? = null
        @Volatile var finishReason: String? = null
        @Volatile var framesFed = 0
        @Volatile var lateTicks = 0
        @Volatile var maxTickGapMs = 0L
        @Volatile var activeAt = 0L
        val finished = AtomicBoolean()
        val done = CountDownLatch(1)
    }
    private val closed = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "RTC-Test-Call") }
    private val ender = Executors.newSingleThreadExecutor { Thread(it, "RTC-Test-Call-End") }
    private val audioLoader = Executors.newSingleThreadExecutor { Thread(it, "RTC-Test-Call-Audio") }
    private val clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "RTC-Test-Call-Clock").apply { priority = Thread.MAX_PRIORITY } }
    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 64
    }
    @Volatile private var active: Active? = null
    val busy: Boolean get() = active != null

    private fun event(call: Active, text: String) {
        val line = "media=webrtc call_control=rtc call_id=${call.callId} test_call=true event=$text elapsed_ms=${SystemClock.elapsedRealtime() - call.startedAt}"
        Log.i("GsmRtc", line)
        runCatching { log(line) }
    }

    /** Pending requests from one node's call list. Each request is tried at most once. */
    fun onRequests(node: GatewayCallControl.Node, requests: List<JSONObject>) {
        if (closed.get() || requests.isEmpty() || active != null) return
        val request = synchronized(seen) { requests.firstOrNull { !seen.containsKey(it.optString("request_id")) } } ?: return
        val requestId = request.optString("request_id").takeIf { it.isNotBlank() } ?: return
        synchronized(seen) { seen[requestId] = true }
        if (!idle()) {
            Log.i("GsmRtc", "test_call=true event=test_call_refused request=${requestId.take(8)} reason=gateway_busy")
            runCatching { log("test_call_refused request=${requestId.take(8)} reason=gateway_busy") }
            return
        }
        val call = Active(node, requestId, UUID.randomUUID().toString())
        synchronized(this) { if (active != null) return; active = call }
        try { worker.execute { run(call) } } catch (_: Exception) { synchronized(this) { if (active === call) active = null } }
    }

    /** Every snapshot from the polls; only the active test call's is used. */
    fun onSnapshot(node: GatewayCallControl.Node, snapshot: JSONObject) {
        val call = active ?: return
        if (call.node !== node || snapshot.optString("call_id") != call.callId) return
        call.snapshot = snapshot
        endReason(snapshot)?.let { reason -> runCatching { ender.execute { finish(call, reason) } } }
    }

    /** Why the node says the call is over, or null while it is not. */
    private fun endReason(snapshot: JSONObject): String? {
        val state = snapshot.optString("state")
        if (!snapshot.optBoolean("end_requested") && state != "ended" && state != "unknown") return null
        return when {
            state == "unknown" -> "control_expired"
            snapshot.optString("reason").isNotBlank() -> "remote_" + snapshot.optString("reason")
            else -> "remote_end"
        }
    }

    /** Ends the active test call for a real cellular call; true once the node has it ended. */
    fun preempt(timeoutMs: Long): Boolean {
        val call = active ?: return true
        runCatching { ender.execute { finish(call, "preempted_by_cellular_call") } }
        return call.done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun sims(node: GatewayCallControl.Node): List<Pair<String, Long>> =
        if (node.primary) runCatching { SimRegistry.snapshot(context).mappings.map { it.simId to it.mappingRevision } }.getOrDefault(emptyList())
        else GatewayDatabase.get(context).meshNodeSims(node.gatewayId).filter { it.state == "active" }.map { it.simId to it.mappingRevision }

    private fun createCall(call: Active): JSONObject? {
        val candidates = sims(call.node)
        if (candidates.isEmpty()) { event(call, "test_call_failed reason=no_bound_sim"); return null }
        for ((sim, revision) in candidates) {
            try {
                return call.node.control.create(call.callId, call.node.gatewayId, sim, revision, "", incoming = true, from = "测试通话",
                    remainingRingMs = if (call.node.receptionV2) RING_MS else null, testRequestId = call.requestId)
            } catch (error: MediaApiException) {
                if (error.status == 409 && error.code == "SIM_MAPPING_CHANGED") {
                    event(call, "test_call_sim_skipped sim=${sim.take(8)} code=${error.code}")
                    continue
                }
                event(call, "test_call_failed stage=create status=${error.status} code=${error.code}")
                return null
            }
        }
        event(call, "test_call_failed reason=no_usable_sim tried=${candidates.size}")
        return null
    }

    /** Waits until [ready] holds for the latest snapshot, the call ends, or [timeoutMs] passes. */
    private fun await(call: Active, timeoutMs: Long, ready: (JSONObject) -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!call.finished.get() && SystemClock.elapsedRealtime() < until) {
            call.snapshot?.let { if (ready(it)) return true }
            Thread.sleep(100)
        }
        return false
    }

    private fun run(call: Active) {
        event(call, "test_call_accepted request=${call.requestId.take(8)} node=${call.node.gatewayId.take(8)} force_relay=${BuildConfig.FORCE_RTC_RELAY}")
        val audio: Future<TestCallAudio.Decoded> = audioLoader.submit<TestCallAudio.Decoded> { TestCallAudio.load(context) }
        try {
            val created = createCall(call) ?: return finish(call, "create_failed")
            call.snapshot = created
            event(call, "test_call_ringing sim=${created.optString("sim_id").take(8)} ring_window_ms=${created.optLong("remaining_ms", -1)}")
            if (!await(call, (if (call.node.receptionV2) RING_MS else 25_000L) + 5_000L) { it.optString("candidate_client_id").isNotBlank() })
                return finish(call, call.finishReason ?: "not_answered")
            val snapshot = checkNotNull(call.snapshot)
            val candidate = snapshot.getString("candidate_client_id")
            event(call, "test_call_answered host=${candidate.take(8)}")

            var admission: JSONObject? = null
            val admissionUntil = SystemClock.elapsedRealtime() + 8_000
            while (!call.finished.get() && SystemClock.elapsedRealtime() < admissionUntil) {
                val next = call.node.api.request("GET", "/calls/${call.callId}/media", null)
                if (next.optString("media_transport") == "webrtc") { admission = next; break }
                Thread.sleep(150)
            }
            val authorized = admission ?: return finish(call, call.finishReason ?: "media_not_selected")
            val binding = authorized.getJSONObject("binding")
            event(call, "test_call_media_admitted ice_servers=${authorized.optJSONArray("ice_servers")?.length() ?: 0}")
            val pcm = PcmBridge()
            val media = RtcAudioSession(context, authorized, call.node.api, true, pcm,
                onFailure = { reason -> runCatching { ender.execute { finish(call, "media_$reason") } } },
                onLog = { line -> runCatching { log(line) } },
                forceRelay = BuildConfig.FORCE_RTC_RELAY,
                additionalRelaySources = RelayRepository.resolveForCall(context, binding.getString("owner_id"), call.callId,
                    "node-" + binding.getString("gateway_id"), nodeServers = RelayRepository.servers(authorized.getJSONArray("ice_servers"))),
                currentRelayNodeId = "node-" + binding.getString("gateway_id"))
            call.media = media
            if (call.finished.get()) { media.closeAfterControlEnd(); return }
            media.start()
            val negotiatedUntil = SystemClock.elapsedRealtime() + 20_000
            while (!call.finished.get() && !media.isClosed && SystemClock.elapsedRealtime() < negotiatedUntil) if (media.awaitNegotiated(250)) break
            if (!media.awaitNegotiated(0)) return finish(call, call.finishReason ?: "media_not_negotiated")
            event(call, "test_call_negotiated")
            call.node.control.operation(call.callId, "winner", candidate)
            event(call, "test_call_winner")
            val connectedUntil = SystemClock.elapsedRealtime() + 20_000
            while (!call.finished.get() && !media.isClosed && SystemClock.elapsedRealtime() < connectedUntil) if (media.awaitConnected(250)) break
            if (!media.isConnected) return finish(call, call.finishReason ?: "media_not_connected")
            event(call, "test_call_connected")
            val decoded = audio.get(15, TimeUnit.SECONDS)
            event(call, "test_call_audio_ready samples=${decoded.pcm.size} duration_ms=${decoded.pcm.size * 1000L / TestCallAudio.RATE}" +
                " source_rate=${decoded.sourceRate} source_channels=${decoded.sourceChannels} decode_ms=${decoded.decodeMs} cached=${decoded.cached}")
            media.enableAudio(true)
            media.markCellularActive()
            val activeUntil = SystemClock.elapsedRealtime() + 5_000
            while (!call.finished.get() && !media.cellularActive && SystemClock.elapsedRealtime() < activeUntil) Thread.sleep(50)
            if (!media.cellularActive) return finish(call, call.finishReason ?: "media_active_rejected")
            call.node.control.operation(call.callId, "active")
            call.activeAt = SystemClock.elapsedRealtime()
            event(call, "test_call_active")
            play(call, pcm, decoded.pcm)
        } catch (error: Throwable) {
            val detail = (error as? MediaApiException)?.let { " status=${it.status} code=${it.code}" } ?: ""
            finish(call, call.finishReason ?: ("error_" + error.javaClass.simpleName + detail.replace(' ', '_')))
        }
    }

    /** Feeds one 20 ms frame per tick, looping the recording until either side
     * hangs up (at most [MAX_PLAY_FRAMES]), and logs progress every 10 s. */
    private fun play(call: Active, pcm: PcmBridge, music: ShortArray) {
        val frames = (music.size + TestCallAudio.FRAME_SAMPLES - 1) / TestCallAudio.FRAME_SAMPLES
        var lastTick = 0L
        call.feeder = clock.scheduleAtFixedRate({
            if (call.finished.get()) return@scheduleAtFixedRate
            val now = SystemClock.elapsedRealtime()
            if (lastTick != 0L) {
                val gap = now - lastTick
                if (gap > 30) call.lateTicks++
                if (gap > call.maxTickGapMs) call.maxTickGapMs = gap
            }
            lastTick = now
            val index = call.framesFed
            if (index >= MAX_PLAY_FRAMES) {
                runCatching { ender.execute { finish(call, "max_duration") } }
                return@scheduleAtFixedRate
            }
            runCatching { pcm.capture(TestCallAudio.frame(music, index % frames), TestCallAudio.RATE) }
            call.framesFed = index + 1
            if ((index + 1) % 500 == 0) event(call, "test_call_playback played_s=${(index + 1) / 50} loop=${index / frames + 1} position_s=${(index % frames + 1) / 50} of_s=${frames / 50}" +
                " late_ticks=${call.lateTicks} max_tick_gap_ms=${call.maxTickGapMs} pcm_overruns=${pcm.overruns.get()} pcm_underruns=${pcm.underruns.get()}")
        }, 0, 20, TimeUnit.MILLISECONDS)
    }

    /** Ends the call on the node (the gateway's "cellular END"), then local media. Idempotent. */
    private fun finish(call: Active, reason: String) {
        if (!call.finished.compareAndSet(false, true)) return
        // A host hang-up tears media down before the next snapshot poll
        // arrives, so a media failure is first checked against the node.
        val cause = if (!reason.startsWith("media_")) reason else runCatching { call.node.control.get(call.callId) }.getOrNull()
            ?.also { call.snapshot = it }?.let(::endReason) ?: reason
        call.finishReason = cause
        call.feeder?.cancel(false)
        // A create that failed ambiguously may still have committed: end it if the node has it.
        if (call.snapshot == null) call.snapshot = runCatching { call.node.control.get(call.callId) }.getOrNull()
        val state = call.snapshot?.optString("state")
        var reported = state == "ended"
        if (!reported && call.snapshot != null) {
            for (attempt in 1..3) {
                try { call.node.control.operation(call.callId, "ended"); reported = true; break }
                catch (error: MediaApiException) {
                    if (error.status == 404 || error.status == 409) { reported = runCatching { call.node.control.get(call.callId).optString("state") == "ended" }.getOrDefault(false); if (reported) break }
                } catch (_: Exception) {}
                Thread.sleep(300L * attempt)
            }
        }
        runCatching { call.media?.closeAfterControlEnd() }
        val played = if (call.activeAt == 0L) 0L else SystemClock.elapsedRealtime() - call.activeAt
        event(call, "test_call_finished reason=$cause${if (cause != reason) " local_reason=$reason" else ""} reported_ended=$reported played_ms=$played frames=${call.framesFed}" +
            " late_ticks=${call.lateTicks} max_tick_gap_ms=${call.maxTickGapMs}")
        synchronized(this) { if (active === call) active = null }
        call.done.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active?.let { call -> runCatching { ender.execute { finish(call, "gateway_stopped") } } }
        worker.shutdownNow(); audioLoader.shutdownNow(); clock.shutdownNow(); ender.shutdown()
    }

    private companion object {
        const val RING_MS = 60_000L
        /** 30 minutes of 20 ms frames: long enough for relay and revocation tests. */
        const val MAX_PLAY_FRAMES = 30 * 60 * 50
    }
}
