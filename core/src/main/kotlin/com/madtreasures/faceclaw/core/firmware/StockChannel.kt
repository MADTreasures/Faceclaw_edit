package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.BleLink
import com.madtreasures.faceclaw.core.protocol.Envelope
import com.madtreasures.faceclaw.core.protocol.FrameReassembler
import com.madtreasures.faceclaw.core.protocol.G2Gatt
import com.madtreasures.faceclaw.core.protocol.G2Messages
import com.madtreasures.faceclaw.core.protocol.InboundMessage
import com.madtreasures.faceclaw.core.protocol.Sid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stock-protocol plumbing shared by the firmware flows, on top of a [FirmwareLink]: envelope
 * framing with its own sequence counter, magic allocation, reassembly of control notifications,
 * replies matched by (arm, sid, magic), the authentication exchange, async events and the queue
 * of OTA acknowledgements. Notifications may arrive on any thread.
 */
internal class StockChannel(
    private val link: FirmwareLink,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val magics: IntRange = 100..255,
    seqStart: Int = 0x40,
) : BleLink.Listener {
    private val lock = Any()
    private val reassemblers = HashMap<Arm, FrameReassembler>()
    private val waiters = HashMap<String, CompletableDeferred<InboundMessage>>()
    private var nextMagic = magics.first
    private var nextSeq = seqStart and 0xFF

    /** Acknowledgements from the OTA notify characteristic. */
    val otaAcks = Channel<Pair<Arm, OtaAck>>(Channel.UNLIMITED)

    /** Async notifications (flag 0x01/0x06) such as list selections. */
    val events = Channel<Pair<Arm, InboundMessage>>(Channel.UNLIMITED)

    /** Replies on sid 0x80; authentication needs every one of them, not just the first. */
    private val authReplies = Channel<Pair<Arm, InboundMessage>>(Channel.UNLIMITED)

    @Volatile var onDisconnected: ((Arm) -> Unit)? = null

    init {
        link.listener = this
    }

    fun allocMagic(): Int = synchronized(lock) {
        val m = nextMagic
        nextMagic = if (nextMagic >= magics.last) magics.first else nextMagic + 1
        m
    }

    private fun seq(): Int = synchronized(lock) {
        val s = nextSeq
        nextSeq = (nextSeq + 1) and 0xFF
        s
    }

    // ------------------------------------------------------------------ inbound

    override fun onNotification(arm: Arm, characteristic: String, value: ByteArray) {
        when {
            characteristic.equals(G2Gatt.OTA_NOTIFY, ignoreCase = true) ->
                OtaProtocol.parseAck(value)?.let { otaAcks.trySend(arm to it) }
            characteristic.equals(G2Gatt.CONTROL_NOTIFY, ignoreCase = true) -> synchronized(lock) {
                reassemblers.getOrPut(arm) { FrameReassembler({ m -> onMessage(arm, m) }, {}, clock) }.push(value)
            }
        }
    }

    /** Called with [lock] held. */
    private fun onMessage(arm: Arm, msg: InboundMessage) {
        if (msg.isNotify) {
            events.trySend(arm to msg)
            return
        }
        if (msg.sid == Sid.DEV_CONFIG) {
            authReplies.trySend(arm to msg)
            return
        }
        waiters.remove(key(arm, msg.sid, msg.magic))?.complete(msg)
    }

    override fun onDisconnected(arm: Arm) {
        log("$arm temple disconnected")
        onDisconnected?.invoke(arm)
    }

    // ------------------------------------------------------------------ outbound

    private fun writeSize(arm: Arm): Int = minOf(Envelope.MAX_WRITE, maxOf(23, link.mtu(arm)) - 3)

    /** Frames and writes one control message without waiting for anything. */
    suspend fun send(arm: Arm, sid: Int, flag: Int, pb: ByteArray) {
        link.write(arm, G2Gatt.CONTROL_WRITE, Envelope.frame(pb, sid, flag, seq(), writeSize(arm)))
    }

    /**
     * Sends a request built for a fresh magic and waits for the reply with the same sid and magic.
     * Returns null on timeout or when the write fails.
     */
    suspend fun request(arm: Arm, sid: Int, flag: Int, timeoutMs: Long, magic: Int = allocMagic(), build: (Int) -> ByteArray): InboundMessage? {
        val reply = CompletableDeferred<InboundMessage>()
        val key = key(arm, sid, magic)
        synchronized(lock) { waiters[key] = reply }
        try {
            send(arm, sid, flag, build(magic))
            return withTimeoutOrNull(timeoutMs) { reply.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("write to $arm failed: ${e.message}")
            return null
        } finally {
            synchronized(lock) { if (waiters[key] === reply) waiters.remove(key) }
        }
    }

    /**
     * The security authentication exchange. The glasses answer a request right away with a
     * non-success result and send the success reply (same magic, empty field 3) only once the link
     * is encrypted; on an unbonded phone that means waiting for the user to accept Android's
     * pairing dialog. The request is re-sent once shortly after a fresh bond, in case the first
     * one was lost during pairing. Returns true on success.
     */
    suspend fun authenticate(arm: Arm, timeoutMs: Long = 30_000, pairingTimeoutMs: Long = 90_000): Boolean {
        while (authReplies.tryReceive().isSuccess) Unit
        val start = clock()
        val initiallyUnbonded = link.isBonded(arm) == false
        var deadline = start + if (initiallyUnbonded) pairingTimeoutMs else timeoutMs
        val sent = HashSet<Int>()
        var sends = 0
        var lastSendFailed = false
        var nextSendAt = start
        var bondedAt = -1L
        var resentAfterBond = false
        suspend fun sendRequest() {
            val magic = allocMagic()
            sent += magic
            sends++
            lastSendFailed = try {
                send(arm, Sid.DEV_CONFIG, Envelope.FLAG_NONE, G2Messages.authRequest(magic))
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("auth request to $arm failed: ${e.message}")
                true
            }
            nextSendAt = clock() + 1000
        }
        sendRequest()
        while (true) {
            val now = clock()
            if (initiallyUnbonded && bondedAt < 0 && link.isBonded(arm) == true) {
                bondedAt = now
                deadline = maxOf(deadline, now + 6000)
                log("$arm temple bonded; waiting for the authentication result")
            }
            if (bondedAt >= 0 && !resentAfterBond && now - bondedAt >= 2000) {
                resentAfterBond = true
                sendRequest()
            } else if (lastSendFailed && sends < 3 && now >= nextSendAt) {
                sendRequest()
            }
            if (now >= deadline) break
            val r = withTimeoutOrNull(250) { authReplies.receive() }
            if (r != null && r.first == arm && r.second.magic in sent) {
                if (G2Messages.isAuthSuccess(r.second)) return true
                log("$arm: authentication not confirmed yet (link not encrypted)")
            }
            if (!link.isConnected(arm)) return false
        }
        log("$arm: authentication timed out")
        return false
    }

    fun drainOtaAcks() {
        while (otaAcks.tryReceive().isSuccess) Unit
    }

    fun close() {
        synchronized(lock) {
            waiters.values.forEach { it.cancel() }
            waiters.clear()
        }
        if (link.listener === this) link.listener = null
    }

    private fun key(arm: Arm, sid: Int, magic: Int) = "$arm:$sid:$magic"
}
