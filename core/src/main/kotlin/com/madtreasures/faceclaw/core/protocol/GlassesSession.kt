package com.madtreasures.faceclaw.core.protocol

import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

enum class SessionPhase { Stopped, Connecting, Connected, Retrying, Charging, IncompatibleFirmware }

data class FirmwareInfo(val leftVersion: String?, val rightVersion: String?, val extension: String?) {
    /** Custom firmware revision ("Faceclaw/<n>"), or null for stock/foreign firmware. */
    val revision: Int? get() = extension?.trim()?.takeIf { it.startsWith("Faceclaw/") }?.removePrefix("Faceclaw/")?.toIntOrNull()
    val isStock: Boolean get() = extension.isNullOrBlank()
}

data class SessionStatus(
    val phase: SessionPhase = SessionPhase.Stopped,
    val detail: String = "",
    val firmware: FirmwareInfo? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val wearing: Boolean? = null,
    val ringBattery: RingBattery? = null,
    val framesPresented: Long = 0,
    /** Time from planning a frame to both lenses acknowledging its PRESENT. */
    val lastFrameLatencyMs: Long? = null,
)

sealed class SessionEvent {
    data class Input(val gesture: Gesture, val source: InputSource) : SessionEvent()
    /** Head-up or similar request to show the display. */
    object Wake : SessionEvent()
    /** "Hey Even" was spoken. */
    object WakeWord : SessionEvent()
    data class Compass(val headingDegrees: Int) : SessionEvent()
}

class SessionConfig(
    /** Oldest custom firmware revision whose contract this session implements. */
    val requiredRevision: Int = 34,
    /** Arm that receives custom-firmware traffic and relays it to the other lens. */
    val cfwIngress: Arm = Arm.Left,
    val compress: Boolean = true,
    val settleMs: Long = 800,
    val authTimeoutMs: Long = 6000,
    val preludeTimeoutMs: Long = 2000,
    val stockAckTimeoutMs: Long = 3500,
    val heartbeatTimeoutMs: Long = 1500,
    val cfwAckTimeoutMs: Long = 500,
    val window: Int = 3,
    val leaseRenewMs: Long = 45_000,
    val heartbeatDueMs: Long = 4000,
    val heartbeatUrgentMs: Long = 6000,
    val keepaliveLostMs: Long = 15_000,
    val settingsPollMs: Long = 300_000,
    val chargingPollMs: Long = 30_000,
    val reconnectDelayMs: Long = 2000,
    val maxReconnectDelayMs: Long = 30_000,
)

class IncompatibleFirmwareException(val info: FirmwareInfo, message: String) : Exception(message)
class TransportFailure(message: String) : Exception(message)

/**
 * One connection to a pair of G2 glasses running the custom firmware.
 *
 * All internal state lives on [scope], which must be single-threaded (e.g.
 * `Dispatchers.Default.limitedParallelism(1)`); the public setters are thread-safe.
 * Rendered frames go in through [submitFrame] (latest wins); input comes out of [events].
 */
class GlassesSession(
    private val link: BleLink,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: SessionConfig = SessionConfig(),
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events

    // ---- thread-safe inputs
    private val desiredFrame = AtomicReference<GrayBitmap?>(null)
    private val desiredSeq = AtomicLong(0)
    @Volatile private var wantVisible = true
    @Volatile private var brightnessPercent = 60
    private val extraCfw = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    fun submitFrame(frame: GrayBitmap) {
        desiredFrame.set(frame)
        desiredSeq.incrementAndGet()
        kick.trySend(Unit)
    }

    /** Fades the lenses out (false) or in with the next frame (true). */
    fun setDisplayVisible(visible: Boolean) {
        wantVisible = visible
        kick.trySend(Unit)
    }

    fun setBrightness(percent: Int) {
        brightnessPercent = percent.coerceIn(0, 100)
        kick.trySend(Unit)
    }

    /** Queues an extra custom-firmware message (buzzer, compass control...). */
    fun sendCfw(message: ByteArray) {
        extraCfw += message
        kick.trySend(Unit)
    }

    // ---- session-thread state
    private enum class Kind { Auth, Prelude, Settings, Page, Heartbeat, Wear, Frame, Present, Control, Cleanup }

    private inner class Out(
        val kind: Kind,
        val arm: Arm,
        val sid: Int,
        val flag: Int,
        val tracked: Boolean,
        val timeoutMs: Long,
        val stock: ((Int) -> ByteArray)? = null,
        val cfw: ByteArray? = null,
        val fixedMagic: Int? = null,
    ) {
        var magic = 0
        var deadline = Long.MAX_VALUE
        var ackedLenses = 0
        var retries = 0
        var retryPending = false
        var plannedAt = 0L
        val crc: Int = cfw?.let { Crc16.compute(it) } ?: 0
        var result: CompletableDeferred<InboundMessage?>? = null
        var onAck: ((InboundMessage?) -> Unit)? = null
        var onTimeout: (() -> Unit)? = null
    }

    private val pending = ArrayDeque<Out>()
    private val inFlight = ArrayList<Out>()
    private val magics = ArrayDeque((100..255).toList())
    private var txSeq = 0x40
    private var encoder = CfwEncoder(config.compress)
    private val reassemblers = HashMap<Arm, FrameReassembler>()
    private val ringFilter = RingInputFilter()

    private var ready = false
    private var linkLost: String? = null
    private var pageCreated = false
    private var pageRequested = false
    private var needPrelude = false
    private var heartbeatOutstanding = false
    private var lastHeartbeatAck = 0L
    private var leaseRenewedAt = 0L
    private var lastSettingsPoll = 0L
    private var lastInputAt = 0L
    private var consecutiveTimeouts = 0
    private var lastSentLevels: ByteArray? = null
    private var plannedSeq = -1L
    private var visibleSent: Boolean? = null
    private var levelSent: Int? = null
    private var charging = false
    private var failures = 0
    private var stopRequest: CompletableDeferred<Unit>? = null
    private var job: Job? = null

    private val listener = object : BleLink.Listener {
        override fun onNotification(arm: Arm, characteristic: String, value: ByteArray) {
            scope.launch { onNotificationOnSession(arm, characteristic, value) }
        }

        override fun onDisconnected(arm: Arm) {
            scope.launch {
                if (ready) {
                    linkLost = "$arm link lost"
                    kick.trySend(Unit)
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    /** Ends the custom-firmware session cleanly (hands the lenses back to the stock UI) and disconnects. */
    suspend fun stop() {
        val j = job ?: return
        val req = CompletableDeferred<Unit>()
        scope.launch {
            if (ready) {
                stopRequest = req
                kick.trySend(Unit)
            } else {
                j.cancel()
                req.complete(Unit)
            }
        }
        withTimeoutOrNull(9000) { req.await() }
        j.cancel()
        j.join()
        runCatching { link.disconnect() }
        encoder.close()
        _status.update { it.copy(phase = SessionPhase.Stopped, detail = "Disconnected") }
    }

    private suspend fun runLoop() {
        while (coroutineContext.isActive) {
            try {
                connect()
                drive()
                return // stopped on request
            } catch (e: CancellationException) {
                throw e
            } catch (e: IncompatibleFirmwareException) {
                log("incompatible firmware: ${e.message}")
                resetState()
                runCatching { link.disconnect() }
                _status.update { it.copy(phase = SessionPhase.IncompatibleFirmware, detail = e.message ?: "", firmware = e.info) }
                return
            } catch (e: Throwable) {
                failures++
                log("transport failure: ${e.message}")
                resetState()
                runCatching { link.disconnect() }
                val wait = min(config.maxReconnectDelayMs, config.reconnectDelayMs * (1L shl min(failures - 1, 4)))
                _status.update { it.copy(phase = SessionPhase.Retrying, detail = "Reconnecting: ${e.message ?: e::class.simpleName}") }
                delay(wait)
            }
        }
    }

    private fun resetState() {
        ready = false
        linkLost = null
        pageCreated = false
        pageRequested = false
        needPrelude = false
        heartbeatOutstanding = false
        charging = false
        consecutiveTimeouts = 0
        for (o in inFlight + pending) o.result?.complete(null)
        inFlight.clear()
        pending.clear()
        magics.clear()
        magics.addAll(100..255)
        encoder.reset()
        lastSentLevels = null
        plannedSeq = -1
        visibleSent = null
        levelSent = null
        ringFilter.reset()
        reassemblers.values.forEach { it.reset() }
    }

    // ------------------------------------------------------------------ connect sequence

    private suspend fun connect() {
        _status.update { it.copy(phase = SessionPhase.Connecting, detail = "Connecting to the glasses…") }
        link.listener = listener
        for (arm in listOf(Arm.Right, Arm.Left)) {
            reassemblers[arm] = FrameReassembler({ m -> onMessage(arm, m) }, { f -> onCfwAck(arm, f) }, clock)
            link.connect(arm)
        }
        delay(config.settleMs)

        // Security authentication on both arms (soft: pairing may still be completing).
        val authR = stockOut(Kind.Auth, Arm.Right, Sid.DEV_CONFIG, Envelope.FLAG_NONE, config.authTimeoutMs, G2Messages::authRequest)
        val authL = stockOut(Kind.Auth, Arm.Left, Sid.DEV_CONFIG, Envelope.FLAG_NONE, config.authTimeoutMs, G2Messages::authRequest)
        val rR = CompletableDeferred<InboundMessage?>().also { authR.result = it }
        val rL = CompletableDeferred<InboundMessage?>().also { authL.result = it }
        writeOut(authR)
        writeOut(authL)
        withTimeoutOrNull(config.authTimeoutMs) {
            rR.await()
            rL.await()
        }
        dropInFlight(authR)
        dropInFlight(authL)

        sendPrelude()

        // Firmware check before any custom-firmware traffic.
        var info: GlassesEvent.Settings? = null
        repeat(3) {
            if (info == null) info = requestSettings()
        }
        val s = info ?: throw TransportFailure("no settings reply")
        val fw = FirmwareInfo(s.leftVersion, s.rightVersion, s.firmwareExtension)
        _status.update { it.copy(firmware = fw) }
        val rev = fw.revision
        when {
            fw.isStock -> throw IncompatibleFirmwareException(fw, "Stock firmware ${fw.rightVersion ?: ""}: the custom firmware Faceclaw/${config.requiredRevision} is required")
            rev == null -> throw IncompatibleFirmwareException(fw, "Unknown custom firmware \"${fw.extension}\"")
            rev < config.requiredRevision -> throw IncompatibleFirmwareException(fw, "Custom firmware Faceclaw/$rev is too old; Faceclaw/${config.requiredRevision} is required")
        }

        // Take over the framebuffer on both lenses.
        sendLease(G2Messages.CfwOp.FB_ACQUIRE)
        leaseRenewedAt = clock()
        encoder.reset()
        ready = true
        failures = 0
        lastHeartbeatAck = clock()
        lastInputAt = clock()
        _status.update { it.copy(phase = SessionPhase.Connected, detail = "Connected") }
        log("session ready, firmware ${fw.extension}")
    }

    private suspend fun sendPrelude() {
        val prelude = stockOut(Kind.Prelude, Arm.Right, Sid.DASHBOARD, Envelope.FLAG_REQUEST, config.preludeTimeoutMs, fixedMagic = G2Messages.PRELUDE_MAGIC) { G2Messages.prelude() }
        val r = CompletableDeferred<InboundMessage?>().also { prelude.result = it }
        writeOut(prelude)
        val ok = withTimeoutOrNull(config.preludeTimeoutMs) { r.await() }
        dropInFlight(prelude)
        if (ok == null) throw TransportFailure("prelude not acknowledged")
    }

    private suspend fun requestSettings(): GlassesEvent.Settings? {
        val out = stockOut(Kind.Settings, Arm.Right, Sid.SETTINGS, Envelope.FLAG_REQUEST, config.stockAckTimeoutMs, G2Messages::settingsRead)
        val r = CompletableDeferred<InboundMessage?>().also { out.result = it }
        writeOut(out)
        val reply = withTimeoutOrNull(config.stockAckTimeoutMs) { r.await() }
        dropInFlight(out)
        lastSettingsPoll = clock()
        return reply?.let { G2Events.decode(it, true) as? GlassesEvent.Settings }
    }

    private suspend fun sendLease(op: Int) {
        for (arm in listOf(Arm.Right, Arm.Left)) {
            link.write(arm, Envelope.frame(G2Messages.cfwControl(op), Sid.SETTINGS, Envelope.FLAG_REQUEST, nextSeq()))
        }
    }

    // ------------------------------------------------------------------ steady state

    private suspend fun drive() {
        while (true) {
            linkLost?.let { throw TransportFailure(it) }
            val now = clock()
            drainCfw()

            // go-back-N replay of custom-firmware messages
            if (inFlight.any { it.cfw != null && (it.retryPending || (it.ackedLenses != 3 && now >= it.deadline)) }) {
                replayCfw()
                continue
            }
            // stock ack timeouts
            val expired = inFlight.firstOrNull { it.cfw == null && now >= it.deadline }
            if (expired != null) {
                inFlight.remove(expired)
                release(expired)
                consecutiveTimeouts++
                log("ack timeout: ${expired.kind}")
                expired.result?.complete(null)
                expired.onTimeout?.invoke()
                if (consecutiveTimeouts > 8) throw TransportFailure("too many acknowledgement timeouts")
                continue
            }
            if (now - leaseRenewedAt >= config.leaseRenewMs) {
                sendLease(G2Messages.CfwOp.FB_ACQUIRE)
                leaseRenewedAt = now
            }

            stopRequest?.let { req ->
                finishSession()
                req.complete(Unit)
                return
            }

            if (charging) {
                if (inFlight.isEmpty() && now - lastSettingsPoll >= config.chargingPollMs) {
                    enqueueSettingsPoll()
                    writeOut(pending.removeFirst())
                }
                waitForWork(1000)
                continue
            }

            // the input page (needed for input events and to keep EvenHub alive)
            if (!pageCreated && !pageRequested && inFlight.isEmpty() && pending.isEmpty()) {
                if (needPrelude) {
                    sendPrelude()
                    needPrelude = false
                }
                pageRequested = true
                val page = stockOut(Kind.Page, Arm.Right, Sid.EVENHUB, Envelope.FLAG_REQUEST, config.stockAckTimeoutMs, G2Messages::createInputPage)
                page.onAck = {
                    pageCreated = true
                    lastHeartbeatAck = 0 // heartbeat right away
                    enqueueWearSetup()
                }
                page.onTimeout = { throw TransportFailure("input page not acknowledged") }
                pending.addFirst(page)
            }

            val tracked = inFlight.count { it.tracked }
            val room = tracked < config.window
            var blocked = false
            if (pageCreated) {
                val elapsed = now - lastHeartbeatAck
                if (elapsed >= config.keepaliveLostMs && lastHeartbeatAck != 0L) throw TransportFailure("keepalive lost")
                when {
                    heartbeatOutstanding -> blocked = true
                    elapsed >= config.heartbeatUrgentMs -> {
                        if (inFlight.isEmpty()) {
                            writeOut(heartbeat())
                            continue
                        }
                        blocked = true
                    }
                    elapsed >= config.heartbeatDueMs && inFlight.isEmpty() && pending.none { it.kind == Kind.Frame || it.kind == Kind.Present } -> {
                        writeOut(heartbeat())
                        continue
                    }
                }
            }
            if (!blocked && room && pending.isNotEmpty()) {
                writeOut(pending.removeFirst())
                continue
            }
            if (!blocked && room && pageCreated) {
                extraCfw.poll()?.let { m ->
                    pending.addLast(cfwOut(Kind.Control, m))
                    continue
                }
                if (pending.none { it.kind == Kind.Frame || it.kind == Kind.Present } && planFrame(now)) continue
            }
            if (!blocked && inFlight.isEmpty() && pending.isEmpty() && now - lastSettingsPoll >= config.settingsPollMs && now - lastInputAt >= 5000) {
                enqueueSettingsPoll()
                continue
            }
            // sleep until something happens or the next deadline
            var next = now + 1000
            inFlight.forEach { if (it.deadline != Long.MAX_VALUE) next = min(next, it.deadline) }
            if (pageCreated && !heartbeatOutstanding) next = min(next, lastHeartbeatAck + config.heartbeatDueMs)
            next = min(next, leaseRenewedAt + config.leaseRenewMs)
            waitForWork(max(1L, next - now))
        }
    }

    private suspend fun waitForWork(ms: Long) {
        withTimeoutOrNull(ms) { kick.receive() }
    }

    private fun heartbeat(): Out {
        heartbeatOutstanding = true
        val hb = stockOut(Kind.Heartbeat, Arm.Right, Sid.EVENHUB, Envelope.FLAG_REQUEST, config.heartbeatTimeoutMs, G2Messages::heartbeat)
        hb.onAck = {
            heartbeatOutstanding = false
            lastHeartbeatAck = clock()
        }
        hb.onTimeout = { heartbeatOutstanding = false }
        return hb
    }

    private fun enqueueSettingsPoll() {
        lastSettingsPoll = clock()
        val out = stockOut(Kind.Settings, Arm.Right, Sid.SETTINGS, Envelope.FLAG_REQUEST, config.stockAckTimeoutMs, G2Messages::settingsRead)
        pending.addLast(out)
    }

    private fun enqueueWearSetup() {
        pending.addLast(stockOut(Kind.Wear, Arm.Right, Sid.SETTINGS, Envelope.FLAG_REQUEST, config.stockAckTimeoutMs) { m -> G2Messages.wearDetection(m, true) })
        for (arm in listOf(Arm.Right, Arm.Left)) {
            pending.addLast(Out(Kind.Control, arm, Sid.SETTINGS, Envelope.FLAG_REQUEST, tracked = false, timeoutMs = 0, stock = { G2Messages.cfwControl(G2Messages.CfwOp.WEAR_QUERY) }))
        }
    }

    /** Plans the newest desired frame as CFW messages; returns true when something was queued. */
    private fun planFrame(now: Long): Boolean {
        val visible = wantVisible
        val level = 2 + brightnessPercent * 98 / 100
        if (!visible) {
            if (visibleSent != false) {
                pending.addLast(cfwOut(Kind.Control, CfwDraw.brightness(levelSent ?: level, false, 280)))
                visibleSent = false
                return true
            }
            return false
        }
        val bmp = desiredFrame.get() ?: return false
        val seq = desiredSeq.get()
        val wake = visibleSent != true
        val levelChange = levelSent != level
        if (seq == plannedSeq && !wake && !levelChange) return false
        val msgs = ArrayList<Pair<Kind, ByteArray>>()
        if (wake || levelChange) {
            msgs += Kind.Control to CfwDraw.brightness(level, true, if (wake) 280 else 120)
            visibleSent = true
            levelSent = level
        }
        var drew = false
        if (seq != plannedSeq) {
            val levels = FrameEncoder.quantize(bmp)
            val prev = lastSentLevels
            val calls = if (prev == null) FrameEncoder.keyframe(levels) else FrameEncoder.diff(prev, levels)
            for (m in CfwDraw.drawMessages(calls)) msgs += Kind.Frame to m
            drew = calls.isNotEmpty()
            lastSentLevels = levels
            plannedSeq = seq
        }
        // After a wake the firmware only fades in once a newer PRESENT arrives.
        if (drew || wake) msgs += Kind.Present to CfwDraw.present()
        if (msgs.isEmpty()) return false
        for ((k, m) in msgs) pending.addLast(cfwOut(k, m).also { it.plannedAt = now })
        return true
    }

    private suspend fun finishSession() {
        // let in-flight messages drain, then hand the lenses back to the stock UI
        pending.clear()
        withTimeoutOrNull(4000) {
            while (inFlight.isNotEmpty()) {
                drainCfw()
                if (inFlight.isEmpty()) break
                waitForWork(50)
                val now = clock()
                inFlight.removeAll { it.cfw == null && now >= it.deadline }
                if (inFlight.any { it.cfw != null && now >= it.deadline }) break
            }
        }
        inFlight.clear()
        val cleanup = cfwOut(Kind.Cleanup, CfwDraw.cleanup())
        val done = CompletableDeferred<InboundMessage?>()
        cleanup.onAck = { done.complete(null) }
        writeOut(cleanup)
        val acked = withTimeoutOrNull(4000) {
            while (!done.isCompleted) {
                drainCfw()
                if (done.isCompleted) break
                waitForWork(50)
            }
            true
        }
        if (acked == null) runCatching { sendLease(G2Messages.CfwOp.FB_RELEASE) }
        ready = false
    }

    // ------------------------------------------------------------------ writing

    private fun nextSeq(): Int {
        val s = txSeq
        txSeq = (txSeq + 1) and 0xFF
        return s
    }

    private fun stockOut(kind: Kind, arm: Arm, sid: Int, flag: Int, timeout: Long, build: (Int) -> ByteArray): Out =
        Out(kind, arm, sid, flag, tracked = true, timeoutMs = timeout, stock = build)

    private fun stockOut(kind: Kind, arm: Arm, sid: Int, flag: Int, timeout: Long, fixedMagic: Int, build: (Int) -> ByteArray): Out =
        Out(kind, arm, sid, flag, tracked = true, timeoutMs = timeout, stock = build, fixedMagic = fixedMagic)

    private fun cfwOut(kind: Kind, message: ByteArray): Out =
        Out(kind, config.cfwIngress, Sid.CFW, 0, tracked = true, timeoutMs = config.cfwAckTimeoutMs, cfw = message)

    private fun allocMagic(): Int = magics.removeFirstOrNull() ?: throw TransportFailure("magic pool exhausted")

    private fun release(o: Out) {
        if (o.magic in 100..255 && o.fixedMagic == null) magics.addLast(o.magic)
        o.magic = 0
    }

    private fun dropInFlight(o: Out) {
        if (inFlight.remove(o)) release(o)
    }

    private suspend fun writeOut(o: Out) {
        if (o.tracked) o.magic = o.fixedMagic ?: allocMagic()
        val frames = if (o.cfw != null) {
            encoder.encode(o.cfw, o.magic, CfwEncoder.LENS_BOTH, link.mtu(o.arm))
        } else {
            Envelope.frame(o.stock!!(o.magic), o.sid, o.flag, nextSeq())
        }
        if (o.tracked) inFlight += o
        try {
            link.write(o.arm, frames)
        } catch (e: Throwable) {
            if (o.cfw != null) encoder.reset()
            throw TransportFailure("write failed: ${e.message}")
        }
        if (o.tracked && inFlight.contains(o)) o.deadline = clock() + o.timeoutMs
    }

    private fun replayCfw() {
        val batch = inFlight.filter { it.cfw != null }
        for (o in batch) if (o.retries >= 3) throw TransportFailure("custom firmware message failed after retries")
        inFlight.removeAll(batch.toSet())
        for (o in batch.asReversed()) {
            release(o)
            o.retries++
            o.ackedLenses = 0
            o.retryPending = false
            o.deadline = Long.MAX_VALUE
            pending.addFirst(o)
        }
        encoder.reset()
        log("replaying ${batch.size} custom-firmware message(s)")
    }

    private fun drainCfw() {
        while (true) {
            val head = inFlight.firstOrNull { it.cfw != null } ?: return
            if (head.ackedLenses != 3 || head.retryPending) return
            inFlight.remove(head)
            release(head)
            consecutiveTimeouts = 0
            lastHeartbeatAck = clock() // every custom-firmware message also resets the EvenHub keep-alive
            if (head.kind == Kind.Present) {
                val latency = clock() - head.plannedAt
                _status.update { it.copy(framesPresented = it.framesPresented + 1, lastFrameLatencyMs = latency) }
            }
            head.onAck?.invoke(null)
            head.result?.complete(null)
        }
    }

    // ------------------------------------------------------------------ receiving

    private fun onNotificationOnSession(arm: Arm, characteristic: String, value: ByteArray) {
        if (!characteristic.equals(G2Gatt.CONTROL_NOTIFY, ignoreCase = true)) return
        reassemblers[arm]?.push(value)
        kick.trySend(Unit)
    }

    private fun onCfwAck(arm: Arm, frame: ByteArray) {
        val acks = CfwAcks.parse(frame) ?: return
        for (a in acks) {
            val o = inFlight.firstOrNull { it.cfw != null && it.arm == arm && it.magic == a.streamId } ?: continue
            if (a.ordinal != 0) continue
            if (!a.isAck) {
                o.retryPending = true
            } else if (a.size == o.cfw!!.size && a.crc == o.crc) {
                o.ackedLenses = o.ackedLenses or a.lens
            }
        }
        drainCfw()
    }

    private fun onMessage(arm: Arm, m: InboundMessage) {
        val event = G2Events.decode(m, arm == Arm.Right)
        if (event != null) handleEvent(event)
        // acknowledgement matching (replies echo the request's magic)
        if (m.isNotify) return
        if (m.sid == Sid.ONBOARDING) return
        if (m.sid == Sid.SETTINGS && (m.command == 3 || m.proto?.has(102) == true)) return
        val magic = m.magic
        if (magic <= 0) return
        val o = inFlight.firstOrNull { it.cfw == null && it.sid == m.sid && it.magic == magic } ?: return
        inFlight.remove(o)
        release(o)
        consecutiveTimeouts = 0
        o.onAck?.invoke(m)
        o.result?.complete(m)
    }

    private fun handleEvent(e: GlassesEvent) {
        when (e) {
            is GlassesEvent.Gesture -> {
                if (e.type == G2Events.HEAD_UP) {
                    _events.tryEmit(SessionEvent.Wake)
                    return
                }
                if (!ringFilter.accept(e.ring)) return
                if (e.ring != null && e.type == 127) return
                val mapped = G2Events.toInput(e) ?: return
                lastInputAt = clock()
                _events.tryEmit(SessionEvent.Input(mapped.first, mapped.second))
            }
            is GlassesEvent.PageExit -> {
                log("page closed by the glasses (${e.type})")
                pageCreated = false
                pageRequested = false
                needPrelude = true
                pending.removeAll { it.kind == Kind.Frame || it.kind == Kind.Present }
                lastSentLevels = null
            }
            is GlassesEvent.Wear -> _status.update { it.copy(wearing = e.onHead) }
            is GlassesEvent.Settings -> {
                val nowCharging = e.charging == true
                if (charging && !nowCharging && ready) linkLost = "charging ended"
                charging = nowCharging
                _status.update {
                    it.copy(
                        battery = e.battery ?: it.battery,
                        charging = nowCharging,
                        ringBattery = e.ringBattery ?: it.ringBattery,
                        phase = if (nowCharging && ready) SessionPhase.Charging else if (ready) SessionPhase.Connected else it.phase,
                        detail = if (nowCharging && ready) "Glasses charging (${e.battery ?: "?"}%)" else it.detail,
                    )
                }
            }
            is GlassesEvent.RingBatteryPush -> _status.update { it.copy(ringBattery = e.battery) }
            is GlassesEvent.Compass -> _events.tryEmit(SessionEvent.Compass(e.headingDegrees))
            is GlassesEvent.EvenAi -> if (e.status == 1) _events.tryEmit(SessionEvent.WakeWord)
            is GlassesEvent.WakeRequest, is GlassesEvent.IdleGesture -> {}
        }
    }
}
