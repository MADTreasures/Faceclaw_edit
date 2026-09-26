package com.madtreasures.faceclaw.core.protocol

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, no final XOR). Check value "123456789" = 0x29B1. */
object Crc16 {
    private val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, init: Int = 0xFFFF): Int {
        var crc = init
        for (i in offset until offset + length) {
            crc = ((crc shl 8) and 0xFFFF) xor table[((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}

/** Growable little helper for building byte messages. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int): ByteWriter {
        ensure(1)
        buf[size++] = v.toByte()
        return this
    }

    fun u16le(v: Int): ByteWriter {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        return this
    }

    fun s16le(v: Int): ByteWriter = u16le(v and 0xFFFF)

    fun u32le(v: Long): ByteWriter {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteWriter {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun hexBytes(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32le(i: Int): Long = (u16le(i).toLong()) or (u16le(i + 2).toLong() shl 16)
