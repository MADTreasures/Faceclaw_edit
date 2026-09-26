package com.madtreasures.faceclaw.core.gfx

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Minimal PNG codec for 8-bit greyscale images (screenshots, golden tests, simulator). */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /**
     * Encodes [bitmap]. When [tint] is given (0xRRGGBB), the image is written as RGB with each
     * grey level scaled into that colour, which is how the glasses actually look.
     */
    fun encode(bitmap: GrayBitmap, tint: Int? = null): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val channels = if (tint == null) 1 else 3
        val raw = ByteArray(h * (1 + w * channels))
        var o = 0
        val tr = tint?.let { (it shr 16) and 0xFF } ?: 0
        val tg = tint?.let { (it shr 8) and 0xFF } ?: 0
        val tb = tint?.let { it and 0xFF } ?: 0
        for (y in 0 until h) {
            raw[o++] = 0 // filter: none
            for (x in 0 until w) {
                val v = bitmap.pixels[y * w + x].toInt() and 0xFF
                if (tint == null) {
                    raw[o++] = v.toByte()
                } else {
                    raw[o++] = (v * tr / 255).toByte()
                    raw[o++] = (v * tg / 255).toByte()
                    raw[o++] = (v * tb / 255).toByte()
                }
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also { s ->
            DataOutputStream(s).apply {
                writeInt(w)
                writeInt(h)
                writeByte(8) // bit depth
                writeByte(if (tint == null) 0 else 2) // colour type: grey / RGB
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Decodes a non-interlaced 8-bit greyscale, grey+alpha, RGB or RGBA PNG to luminance. */
    fun decode(bytes: ByteArray): GrayBitmap {
        require(bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        var pos = 8
        var width = 0
        var height = 0
        var colorType = 0
        val idat = ByteArrayOutputStream()
        fun readInt(p: Int) = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
            ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
        while (pos + 8 <= bytes.size) {
            val len = readInt(pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = readInt(data)
                    height = readInt(data + 4)
                    require(bytes[data + 8].toInt() == 8) { "only 8-bit PNGs are supported" }
                    colorType = bytes[data + 9].toInt()
                    require(bytes[data + 12].toInt() == 0) { "interlaced PNGs are not supported" }
                }
                "IDAT" -> idat.write(bytes, data, len)
                "IEND" -> break
            }
            pos = data + len + 4
        }
        val channels = when (colorType) {
            0 -> 1
            4 -> 2
            2 -> 3
            6 -> 4
            else -> error("unsupported PNG colour type $colorType")
        }
        val stride = width * channels
        val raw = ByteArray(height * (stride + 1))
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        var off = 0
        while (off < raw.size && !inflater.finished()) {
            val n = inflater.inflate(raw, off, raw.size - off)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            off += n
        }
        inflater.end()
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val out = GrayBitmap(width, height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            System.arraycopy(raw, y * (stride + 1) + 1, cur, 0, stride)
            unfilter(filter, cur, prev, channels)
            for (x in 0 until width) {
                val i = x * channels
                val lum = when (channels) {
                    1, 2 -> cur[i].toInt() and 0xFF
                    else -> ((cur[i].toInt() and 0xFF) * 54 + (cur[i + 1].toInt() and 0xFF) * 183 + (cur[i + 2].toInt() and 0xFF) * 19) shr 8
                }
                val alpha = when (channels) {
                    2 -> cur[i + 1].toInt() and 0xFF
                    4 -> cur[i + 3].toInt() and 0xFF
                    else -> 255
                }
                out.pixels[y * width + x] = (lum * alpha / 255).toByte()
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return out
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        for (i in cur.indices) {
            val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            val x = cur[i].toInt() and 0xFF
            val v = when (filter) {
                0 -> x
                1 -> x + a
                2 -> x + b
                3 -> x + ((a + b) ushr 1)
                4 -> {
                    val p = a + b - c
                    val pa = kotlin.math.abs(p - a)
                    val pb = kotlin.math.abs(p - b)
                    val pc = kotlin.math.abs(p - c)
                    x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                }
                else -> error("bad PNG filter $filter")
            }
            cur[i] = v.toByte()
        }
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        d.write(typeBytes)
        d.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        d.writeInt(crc.value.toInt())
    }
}
