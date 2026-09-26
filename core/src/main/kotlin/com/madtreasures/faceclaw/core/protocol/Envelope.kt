package com.madtreasures.faceclaw.core.protocol

/**
 * The stock G2 envelope: `AA 21|12 seq len total index sid flag chunk…`.
 * The payload stream is `protobuf ‖ CRC16(protobuf) LE`, split into chunks of at most 232 bytes;
 * every fragment of one message carries the same `seq`.
 */
object Envelope {
    const val TX = 0x21
    const val RX = 0x12
    const val HEADER = 8
    const val MAX_WRITE = 240
    const val FLAG_REQUEST = 0x20
    const val FLAG_NONE = 0x00
    const val FLAG_NOTIFY = 0x01
    const val FLAG_NOTIFY_ALT = 0x06

    /** Frames [pb] for writing. One returned array = one BLE write. */
    fun frame(pb: ByteArray, sid: Int, flag: Int, seq: Int, maxWrite: Int = MAX_WRITE): List<ByteArray> {
        val chunk = minOf(232, maxWrite - HEADER)
        require(chunk >= 12) { "write size too small" }
        val crc = Crc16.compute(pb)
        val stream = ByteArray(pb.size + 2)
        System.arraycopy(pb, 0, stream, 0, pb.size)
        stream[pb.size] = crc.toByte()
        stream[pb.size + 1] = (crc ushr 8).toByte()
        val total = maxOf(1, (stream.size + chunk - 1) / chunk)
        require(total <= 255) { "message too large for the envelope (${pb.size} bytes)" }
        return List(total) { i ->
            val start = i * chunk
            val end = minOf(stream.size, start + chunk)
            val f = ByteArray(HEADER + end - start)
            f[0] = 0xAA.toByte()
            f[1] = TX.toByte()
            f[2] = seq.toByte()
            f[3] = (end - start).toByte()
            f[4] = total.toByte()
            f[5] = (i + 1).toByte()
            f[6] = sid.toByte()
            f[7] = flag.toByte()
            System.arraycopy(stream, start, f, HEADER, end - start)
            f
        }
    }
}

/** A complete inbound message after reassembly and CRC check. */
class InboundMessage(
    val sid: Int,
    val flag: Int,
    /** Protobuf payload without CRC. */
    val payload: ByteArray,
) {
    val proto: ProtoMessage? by lazy { ProtoMessage.parse(payload) }
    val command: Int get() = proto?.int(1) ?: -1
    val magic: Int get() = proto?.int(2) ?: -1
    val isNotify: Boolean get() = flag == Envelope.FLAG_NOTIFY || flag == Envelope.FLAG_NOTIFY_ALT
}

/**
 * Reassembles notification values from one link into messages. Handles several frames per
 * notification, frames split across notifications, multi-fragment messages and CRC checks.
 * CFW transport frames (sid 0xF0) are returned raw via [onCfwFrame] since they have their own format.
 */
class FrameReassembler(
    private val onMessage: (InboundMessage) -> Unit,
    private val onCfwFrame: (ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var buffer = ByteArray(0)
    private class Partial(val sid: Int, val flag: Int, val total: Int, var next: Int, val data: ByteWriter, var touched: Long)
    private val partials = HashMap<String, Partial>()

    fun push(value: ByteArray) {
        buffer = if (buffer.isEmpty()) value.copyOf() else buffer + value
        while (true) {
            // resync on AA 21 / AA 12
            var start = 0
            while (start + 1 < buffer.size && !(buffer.u8(start) == 0xAA && (buffer.u8(start + 1) == Envelope.RX || buffer.u8(start + 1) == Envelope.TX))) start++
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < Envelope.HEADER) return
            val len = buffer.u8(3)
            val frameLen = Envelope.HEADER + len
            if (buffer.size < frameLen) return
            val frame = buffer.copyOfRange(0, frameLen)
            buffer = buffer.copyOfRange(frameLen, buffer.size)
            handleFrame(frame)
        }
    }

    private fun handleFrame(f: ByteArray) {
        val sid = f.u8(6)
        if (sid == 0xF0) {
            onCfwFrame(f)
            return
        }
        val seq = f.u8(2)
        val total = f.u8(4)
        val idx = f.u8(5)
        val flag = f.u8(7)
        if (total == 0 || idx == 0 || idx > total) return
        val now = clock()
        partials.entries.removeIf { now - it.value.touched > 5000 }
        val key = "$seq:$sid:$flag"
        val chunk = f.copyOfRange(Envelope.HEADER, f.size)
        val p = if (idx == 1) {
            Partial(sid, flag, total, 1, ByteWriter(total * 232), now).also { partials[key] = it }
        } else {
            partials[key] ?: return
        }
        if (p.next != idx || p.total != total) {
            partials.remove(key)
            return
        }
        p.data.bytes(chunk)
        p.next++
        p.touched = now
        if (p.data.size > 65536) {
            partials.remove(key)
            return
        }
        if (idx == total) {
            partials.remove(key)
            val stream = p.data.toByteArray()
            if (stream.size < 2) return
            val body = stream.copyOfRange(0, stream.size - 2)
            val crc = stream.u16le(stream.size - 2)
            if (Crc16.compute(body) != crc) return
            if (ProtoMessage.parse(body) == null) return
            onMessage(InboundMessage(sid, flag, body))
        }
    }

    fun reset() {
        buffer = ByteArray(0)
        partials.clear()
    }
}
