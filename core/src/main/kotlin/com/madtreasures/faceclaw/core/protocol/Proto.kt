package com.madtreasures.faceclaw.core.protocol

/**
 * Minimal protobuf encoding/decoding. The G2 protocol uses a handful of small messages, so
 * a hand-written codec is simpler than generated classes and keeps the core dependency-free.
 */
class ProtoWriter {
    private val out = ByteWriter()

    private fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.u8(x.toInt())
                return
            }
            out.u8(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun key(field: Int, wireType: Int) = varint(((field.toLong()) shl 3) or wireType.toLong())

    fun uint(field: Int, value: Long): ProtoWriter {
        key(field, 0)
        varint(value)
        return this
    }

    fun uint(field: Int, value: Int): ProtoWriter = uint(field, value.toLong() and 0xFFFFFFFFL)

    fun bool(field: Int, value: Boolean): ProtoWriter = uint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        key(field, 2)
        varint(value.size.toLong())
        out.bytes(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun build(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()
    }
}

/** A decoded protobuf message: field number → values in order of appearance. */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {
    val fieldNumbers: Set<Int> get() = fields.keys

    fun has(field: Int) = fields.containsKey(field)

    /** Varint (wire type 0) or fixed32/64 value. */
    fun uint(field: Int): Long? = fields[field]?.firstNotNullOfOrNull { it as? Long }

    fun int(field: Int): Int? = uint(field)?.toInt()

    fun bytes(field: Int): ByteArray? = fields[field]?.firstNotNullOfOrNull { it as? ByteArray }

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun fixed32(field: Int): Int? = fields[field]?.firstNotNullOfOrNull { (it as? Fixed32)?.bits }

    private class Fixed32(val bits: Int)

    companion object {
        /** Parses [data]; returns null when it is not structurally valid protobuf. */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ProtoMessage? {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var p = offset
            val end = offset + length
            fun readVarint(): Long? {
                var result = 0L
                var shift = 0
                while (p < end && shift < 64) {
                    val b = data[p++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                return null
            }
            while (p < end) {
                val key = readVarint() ?: return null
                val field = (key ushr 3).toInt()
                val wt = (key and 7).toInt()
                if (field < 1) return null
                val value: Any = when (wt) {
                    0 -> readVarint() ?: return null
                    1 -> {
                        if (p + 8 > end) return null
                        var v = 0L
                        for (i in 0 until 8) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                        p += 8
                        v
                    }
                    2 -> {
                        val len = readVarint() ?: return null
                        if (len < 0 || p + len > end) return null
                        val b = data.copyOfRange(p, p + len.toInt())
                        p += len.toInt()
                        b
                    }
                    5 -> {
                        if (p + 4 > end) return null
                        val bits = data.u16le(p) or (data.u16le(p + 2) shl 16)
                        p += 4
                        Fixed32(bits)
                    }
                    else -> return null
                }
                fields.getOrPut(field) { ArrayList() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}
