package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.G2Gatt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class OtaTimings(
    /** Time allowed to reach the first lens. */
    val firstLensWindowMs: Long = 30_000,
    /** Time allowed to reach the second lens (both lenses reboot after the first one). */
    val secondLensWindowMs: Long = 120_000,
    val connectRetryDelayMs: Long = 2_500,
    /** Pause after authentication before the first OTA message. */
    val settleMs: Long = 2_500,
    val controlAckMs: Long = 8_000,
    val blockAckMs: Long = 4_000,
    val componentRetryDelayMs: Long = 1_500,
    /** Pause before reconnecting after the link became doubtful. */
    val reconnectDelayMs: Long = 10_000,
    val rebootSettleMs: Long = 5_000,
    val authTimeoutMs: Long = 30_000,
)

data class FlashProgress(
    val arm: Arm,
    /** 0 for the first lens flashed, 1 for the second. */
    val lensIndex: Int,
    val lensCount: Int,
    /** 1-based. */
    val component: Int,
    val components: Int,
    /** 1-based. */
    val block: Int,
    val blocks: Int,
    val bytesSent: Long,
    val bytesTotal: Long,
) {
    /** Overall progress 0..1 across all lenses. */
    val fraction: Float get() = ((lensIndex + bytesSent.toDouble() / bytesTotal) / lensCount).toFloat().coerceIn(0f, 1f)
}

sealed class FlashState {
    data class Connecting(val arm: Arm) : FlashState()
    data class Flashing(val progress: FlashProgress) : FlashState()
    data class Retrying(val arm: Arm, val reason: String) : FlashState()

    /** The first lens is done; both lenses restart before the second one can be reached. */
    object Rebooting : FlashState()
    object Done : FlashState()
}

/** A lens could not be flashed. [flashedLenses] lists the lenses that completed before. */
class FlashFailedException(val arm: Arm, val flashedLenses: List<Arm>, message: String) : Exception(message)

/**
 * Writes an image to both lenses over the stock OTA protocol, left lens first.
 *
 * Per lens: connect with the OTA channel, authenticate, BEGIN, then every component in table
 * order (FILE_CHECK with the subheader, the payload in 4 KB blocks, END where the glasses verify
 * the CRC). The protocol has no block index, so a block is resent in place only after an
 * explicit NAK. Any timeout or write failure makes the link doubtful: the flasher reconnects,
 * authenticates, sends a fresh BEGIN and starts the lens over from the first component —
 * exactly the sequence of a normal flash. If a lens fails, the flash stops there.
 *
 * Nothing but the OTA characteristic is written between BEGIN and the last END.
 */
class OtaFlasher(
    private val link: FirmwareLink,
    private val image: EvenOtaImage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timings: OtaTimings = OtaTimings(),
    private val log: (String) -> Unit = {},
    /** Allow-list check; only tests flash synthetic images. */
    private val isAllowed: (EvenOtaImage) -> Boolean = { FirmwareCatalog.kindOf(it.recomputeSha256()) != null },
) {
    companion object {
        const val BLOCK_SENDS = 3
        const val COMPONENT_ATTEMPTS = 3
        const val RECONNECTS_PER_LENS = 3
        private const val PROGRESS_EVERY = 20
    }

    private class LinkDoubtful(message: String) : Exception(message)
    private class Rejected(message: String) : Exception(message)

    private var otaSeq = 1

    suspend fun flash(lenses: List<Arm> = listOf(Arm.Left, Arm.Right), onState: (FlashState) -> Unit = {}) {
        require(lenses.isNotEmpty() && lenses.distinct().size == lenses.size)
        if (!isAllowed(image)) throw FlashFailedException(lenses.first(), emptyList(), "This image is not on the allow-list.")
        val done = ArrayList<Arm>()
        for ((i, arm) in lenses.withIndex()) {
            if (i > 0) {
                onState(FlashState.Rebooting)
                delay(timings.rebootSettleMs)
            }
            try {
                flashLens(arm, i, lenses.size, if (i == 0) timings.firstLensWindowMs else timings.secondLensWindowMs, onState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlashFailedException) {
                throw FlashFailedException(arm, done.toList(), e.message ?: "failed")
            } catch (e: Exception) {
                throw FlashFailedException(arm, done.toList(), e.message ?: e.toString())
            } finally {
                runCatching { link.disconnect(arm) }
            }
            done += arm
            log("${arm.label} lens flashed")
        }
        onState(FlashState.Done)
    }

    /**
     * Dry run: connects every lens over the update channel, checking the MTU and the
     * authentication, and disconnects again. Nothing is written to the OTA characteristic.
     */
    suspend fun checkLenses(lenses: List<Arm> = listOf(Arm.Left, Arm.Right), onState: (FlashState) -> Unit = {}) {
        if (!isAllowed(image)) throw FlashFailedException(lenses.first(), emptyList(), "This image is not on the allow-list.")
        for (arm in lenses) {
            onState(FlashState.Connecting(arm))
            try {
                connect(arm, clock() + timings.firstLensWindowMs).close()
            } finally {
                runCatching { link.disconnect(arm) }
            }
        }
        onState(FlashState.Done)
    }

    private suspend fun flashLens(arm: Arm, lensIndex: Int, lensCount: Int, window: Long, onState: (FlashState) -> Unit) {
        onState(FlashState.Connecting(arm))
        var ch = connect(arm, clock() + window)
        var reconnects = 0
        try {
            begin(ch, arm, strict = false)
            val attempts = IntArray(image.components.size)
            var sent = 0L
            var index = 0
            while (index < image.components.size) {
                val c = image.components[index]
                attempts[index]++
                try {
                    sent = flashComponent(ch, arm, c, sent) { block ->
                        onState(FlashState.Flashing(progress(arm, lensIndex, lensCount, c, block, sent)))
                    }
                    index++
                } catch (e: Rejected) {
                    log("${arm.label}: ${c.name}: ${e.message}")
                    if (attempts[index] >= COMPONENT_ATTEMPTS) throw FlashFailedException(arm, emptyList(), "${c.name}: ${e.message}")
                    onState(FlashState.Retrying(arm, e.message ?: ""))
                    ch.drainOtaAcks()
                    delay(timings.componentRetryDelayMs)
                    sent = bytesBefore(index)
                } catch (e: LinkDoubtful) {
                    log("${arm.label}: ${c.name}: ${e.message}")
                    if (attempts[index] >= COMPONENT_ATTEMPTS || reconnects >= RECONNECTS_PER_LENS) {
                        throw FlashFailedException(arm, emptyList(), "${c.name}: ${e.message}")
                    }
                    reconnects++
                    onState(FlashState.Retrying(arm, e.message ?: ""))
                    ch.close()
                    ch = recover(arm)
                    // A fresh BEGIN starts a new session: send every component again, in order.
                    index = 0
                    sent = 0
                }
            }
        } finally {
            ch.close()
        }
    }

    private fun bytesBefore(index: Int): Long = image.components.take(index).sumOf { it.size.toLong() }

    private fun progress(arm: Arm, lensIndex: Int, lensCount: Int, c: FirmwareComponent, block: Int, sentBefore: Long) = FlashProgress(
        arm = arm,
        lensIndex = lensIndex,
        lensCount = lensCount,
        component = c.index + 1,
        components = image.components.size,
        block = block,
        blocks = c.blockCount,
        bytesSent = sentBefore + minOf(c.size.toLong(), block.toLong() * EvenOtaImage.BLOCK_SIZE),
        bytesTotal = image.payloadBytes,
    )

    /** Connects [arm] with the OTA channel and authenticates, retrying until [deadline]. */
    private suspend fun connect(arm: Arm, deadline: Long): StockChannel {
        var last = ""
        while (true) {
            val ch = StockChannel(link, clock, log, magics = 0x60..0x7F, seqStart = 1)
            try {
                runCatching { link.disconnect(arm) }
                link.connect(arm, ota = true)
                val mtu = link.mtu(arm)
                if (mtu < OtaProtocol.MIN_MTU) {
                    throw FlashFailedException(arm, emptyList(), "The Bluetooth connection is too small for an update (MTU $mtu, need ${OtaProtocol.MIN_MTU}).")
                }
                if (!ch.authenticate(arm, timings.authTimeoutMs)) throw IllegalStateException("authentication not confirmed")
                delay(timings.settleMs)
                return ch
            } catch (e: CancellationException) {
                ch.close()
                throw e
            } catch (e: FlashFailedException) {
                ch.close()
                throw e
            } catch (e: Exception) {
                ch.close()
                last = e.message ?: e.toString()
                log("${arm.label}: connect attempt failed: $last")
            }
            if (clock() + timings.connectRetryDelayMs >= deadline) {
                throw FlashFailedException(arm, emptyList(), "Could not reach the ${arm.label} lens ($last).")
            }
            delay(timings.connectRetryDelayMs)
        }
    }

    /** Reconnects after a doubtful link and starts a new OTA session. */
    private suspend fun recover(arm: Arm): StockChannel {
        var last = "unknown"
        repeat(RECONNECTS_PER_LENS) { attempt ->
            runCatching { link.disconnect(arm) }
            delay(timings.reconnectDelayMs)
            try {
                val ch = connect(arm, clock() + timings.firstLensWindowMs)
                try {
                    begin(ch, arm, strict = true)
                    return ch
                } catch (e: Exception) {
                    ch.close()
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e.message ?: e.toString()
                log("${arm.label}: reconnect ${attempt + 1} failed: $last")
            }
        }
        throw FlashFailedException(arm, emptyList(), "Lost the connection to the ${arm.label} lens ($last).")
    }

    /** BEGIN; its status is only logged unless [strict] (after a reconnect it must be OK). */
    private suspend fun begin(ch: StockChannel, arm: Arm, strict: Boolean) {
        otaSeq = 1
        val st = sendControl(ch, arm, OtaProtocol.begin(nextSeq()), OtaProtocol.OP_BEGIN)
            ?: throw LinkDoubtful("no answer to BEGIN")
        if (st !in OtaProtocol.END_OK) {
            if (strict) throw LinkDoubtful("BEGIN rejected (${OtaProtocol.statusName(st)})")
            log("${arm.label}: BEGIN status ${OtaProtocol.statusName(st)}; continuing")
        }
    }

    /** One attempt at a component; returns the bytes sent so far on success. */
    private suspend fun flashComponent(ch: StockChannel, arm: Arm, c: FirmwareComponent, sentBefore: Long, onBlock: (Int) -> Unit): Long {
        val check = sendControl(ch, arm, OtaProtocol.fileCheck(image.subheader(c), nextSeq()), OtaProtocol.OP_FILE_CHECK)
            ?: throw LinkDoubtful("no answer to FILE_CHECK")
        if (check != 0) throw Rejected("FILE_CHECK rejected (${OtaProtocol.statusName(check)})")
        for (b in 0 until c.blockCount) {
            val data = image.block(c, b)
            var sends = 0
            while (true) {
                sends++
                val st = sendAndAwait(ch, arm, OtaProtocol.block(data, nextSeq()), OtaProtocol.OP_BLOCK, timings.blockAckMs)
                    ?: throw LinkDoubtful("no answer to block ${b + 1}/${c.blockCount}")
                if (st == 0) break
                log("${arm.label}: ${c.name} block ${b + 1} NAK ${OtaProtocol.statusName(st)} ($sends/$BLOCK_SENDS)")
                if (sends >= BLOCK_SENDS) throw Rejected("block ${b + 1} rejected $BLOCK_SENDS times (${OtaProtocol.statusName(st)})")
            }
            if ((b + 1) % PROGRESS_EVERY == 0 || b == c.blockCount - 1) onBlock(b + 1)
        }
        val end = sendControl(ch, arm, OtaProtocol.end(nextSeq()), OtaProtocol.OP_END)
            ?: throw LinkDoubtful("no answer to END")
        if (end !in OtaProtocol.END_OK) throw Rejected("verification failed (${OtaProtocol.statusName(end)})")
        log("${arm.label}: ${c.name} verified (${OtaProtocol.statusName(end)})")
        return sentBefore + c.size
    }

    private suspend fun sendControl(ch: StockChannel, arm: Arm, frames: List<ByteArray>, op: Int): Int? =
        sendAndAwait(ch, arm, frames, op, timings.controlAckMs)

    /** Clears stale acks, writes [frames] as one transaction and waits for an ack with opcode [op]. */
    private suspend fun sendAndAwait(ch: StockChannel, arm: Arm, frames: List<ByteArray>, op: Int, timeoutMs: Long): Int? {
        ch.drainOtaAcks()
        try {
            link.write(arm, G2Gatt.OTA_WRITE, frames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LinkDoubtful("write failed: ${e.message}")
        }
        return withTimeoutOrNull(timeoutMs) {
            var status: Int? = null
            while (status == null) {
                val (from, ack) = ch.otaAcks.receive()
                if (from == arm && ack.opcode == op) status = ack.status
            }
            status
        }
    }

    private fun nextSeq(): Int {
        val s = otaSeq
        otaSeq = (otaSeq + 1) and 0xFF
        return s
    }
}
