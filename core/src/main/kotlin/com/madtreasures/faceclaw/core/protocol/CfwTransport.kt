package com.madtreasures.faceclaw.core.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Private reliable transport of the custom firmware (sid 0xF0).
 *
 * One message = one stream of packets written to one arm (the "ingress" arm), which relays
 * them to the other lens. Packet: `AA 21 seq len 01 01 F0 00 options chunk… crc16`,
 * where the first packet's seq is the stream id and options carry the lens mask plus
 * RESET (first) / END (last). The stream bytes form one record:
 * `[flags][bodyLen LE16][crc16(decoded) LE16][body]`; the body may be a SYNC_FLUSHed chunk of
 * one persistent zlib stream.
 */
class CfwEncoder(private val compress: Boolean = true) {
    companion object {
        const val LENS_LEFT = 1
        const val LENS_RIGHT = 2
        const val LENS_BOTH = 3
        const val OPT_RESET = 0x80
        const val OPT_END = 0x40
        const val REC_COMPRESSED = 0x04
        const val REC_RESET_CONTEXT = 0x08
        const val MAX_MESSAGE = 65535
    }

    private var deflater: Deflater? = null
    private var resetPending = true
    private var lastLens = -1

    /** Forces the next record to start a fresh compression context. */
    fun reset() {
        deflater?.reset()
        resetPending = true
    }

    fun close() {
        deflater?.end()
        deflater = null
    }

    /** Builds the record (flags, length, CRC, body) for [message]. */
    fun record(message: ByteArray, lensMask: Int): ByteArray {
        require(message.size <= MAX_MESSAGE) { "CFW message too large: ${message.size}" }
        if (lensMask != lastLens) {
            reset()
            lastLens = lensMask
        }
        val crc = Crc16.compute(message)
        var flags: Int
        var body: ByteArray
        if (compress && message.isNotEmpty()) {
            val d = deflater ?: Deflater().also { deflater = it }
            body = deflateSync(d, message)
            if (body.size > MAX_MESSAGE || body.isEmpty()) {
                d.reset()
                resetPending = true
                body = message
                flags = lensMask or REC_RESET_CONTEXT
            } else {
                flags = lensMask or REC_COMPRESSED or (if (resetPending) REC_RESET_CONTEXT else 0)
                resetPending = false
            }
        } else {
            body = message
            flags = lensMask or REC_RESET_CONTEXT
        }
        return ByteWriter(body.size + 5).u8(flags).u16le(body.size).u16le(crc).bytes(body).toByteArray()
    }

    /** Encodes [message] as one stream; returns the BLE writes in order. */
    fun encode(message: ByteArray, streamId: Int, lensMask: Int, mtu: Int): List<ByteArray> =
        packetize(record(message, lensMask), streamId, lensMask, mtu)

    private fun deflateSync(d: Deflater, input: ByteArray): ByteArray {
        d.setInput(input)
        val out = ByteArrayOutputStream(input.size / 2 + 16)
        val buf = ByteArray(4096)
        while (true) {
            val n = d.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            out.write(buf, 0, n)
            if (n < buf.size) break
        }
        return out.toByteArray()
    }
}

/** Splits a record into sid-0xF0 packets. */
fun packetize(record: ByteArray, streamId: Int, lensMask: Int, mtu: Int): List<ByteArray> {
    val capacity = minOf(252, mtu - 14).coerceAtLeast(1)
    val count = maxOf(1, (record.size + capacity - 1) / capacity)
    return List(count) { i ->
        val start = i * capacity
        val end = minOf(record.size, start + capacity)
        val n = end - start
        var options = lensMask
        if (i == 0) options = options or CfwEncoder.OPT_RESET
        if (i == count - 1) options = options or CfwEncoder.OPT_END
        val p = ByteArray(n + 11)
        p[0] = 0xAA.toByte()
        p[1] = Envelope.TX.toByte()
        p[2] = (streamId + i).toByte()
        p[3] = (n + 3).toByte()
        p[4] = 1
        p[5] = 1
        p[6] = Sid.CFW.toByte()
        p[7] = 0
        p[8] = options.toByte()
        System.arraycopy(record, start, p, 9, n)
        val crc = Crc16.compute(p, 8, n + 1)
        p[9 + n] = crc.toByte()
        p[10 + n] = (crc ushr 8).toByte()
        p
    }
}

/** One acknowledgement entry: [kind] 1 = ACK, 3 = NACK; [lens] 1 = left, 2 = right. */
data class CfwAck(val kind: Int, val streamId: Int, val ordinal: Int, val lens: Int, val size: Int, val crc: Int) {
    val isAck: Boolean get() = kind == 1
}

object CfwAcks {
    /**
     * Parses an ACK/NACK notification (19..40 bytes). Returns the primary entry followed by
     * history entries (earlier successes repeated to repair lost ACKs), or null when invalid.
     */
    fun parse(v: ByteArray): List<CfwAck>? {
        val n = v.size
        if (n < 19 || n > 40 || (n - 19) % 7 != 0) return null
        if (v.u8(0) != 0xAA || v.u8(1) != Envelope.RX || v.u8(3) != n - 8) return null
        if (v.u8(4) != 1 || v.u8(5) != 1 || v.u8(6) != Sid.CFW || v.u8(7) != 0) return null
        val kind = v.u8(8)
        val lens = v.u8(12)
        if (kind != 1 && kind != 3) return null
        if (lens != 1 && lens != 2) return null
        if (kind == 3 && n != 19) return null
        if (Crc16.compute(v, 8, n - 10) != v.u16le(n - 2)) return null
        val out = ArrayList<CfwAck>()
        out += CfwAck(kind, v.u8(9), v.u16le(10), lens, v.u16le(13), v.u16le(15))
        for (k in 0 until (n - 19) / 7) {
            val o = 17 + 7 * k
            out += CfwAck(1, v.u8(o), v.u16le(o + 1), lens, v.u16le(o + 3), v.u16le(o + 5))
        }
        return out
    }

    /** Builds an ACK/NACK notification (used by the fake glasses in tests and the loopback mode). */
    fun build(kind: Int, streamId: Int, lens: Int, size: Int, crc: Int, history: List<CfwAck> = emptyList(), txSeq: Int = 0): ByteArray {
        val body = ByteWriter().u8(kind).u8(streamId).u16le(0).u8(lens).u16le(size).u16le(crc)
        if (kind == 1) for (h in history.take(3)) body.u8(h.streamId).u16le(h.ordinal).u16le(h.size).u16le(h.crc)
        val b = body.toByteArray()
        val crcAll = Crc16.compute(b)
        return ByteWriter().u8(0xAA).u8(Envelope.RX).u8(txSeq).u8(b.size + 2).u8(1).u8(1).u8(Sid.CFW).u8(0)
            .bytes(b).u16le(crcAll).toByteArray()
    }
}
