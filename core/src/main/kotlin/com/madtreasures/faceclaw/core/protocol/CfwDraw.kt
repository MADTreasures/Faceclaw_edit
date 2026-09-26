package com.madtreasures.faceclaw.core.protocol

/** Message types of the custom firmware (first byte of a sid-0xF0 message). */
object CfwType {
    const val BUZZER = 5
    const val DIAGNOSTICS = 7
    const val COMPASS = 10
    const val CLEANUP = 11
    const val AMBIENT_LIGHT = 16
    const val RING_BATTERY = 17
    const val DRAW_CALLS = 26
    const val SET_ROOT = 27
    const val PRESENT = 28
    const val BRIGHTNESS = 30
}

/**
 * Encoders for the custom firmware's drawing protocol: pixel RLE, draw calls and the
 * messages that carry them. Pixels are 4-bit levels (0..15); all multi-byte fields are LE.
 */
object CfwDraw {
    const val OP_BBOX = 1
    const val OP_CLEAR = 9
    const val MAX_CALLS = 4096

    /**
     * Exact-pixel RLE of [count] levels starting at [offset]: `[n<<4|c]` for runs of 1..15,
     * `[c][n8]` for 16..255 and `[c][0][lo][hi]` for 256..65535.
     */
    fun rle(pixels: ByteArray, offset: Int, count: Int, out: ByteWriter) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val c = pixels[i].toInt() and 0x0F
            var run = 1
            while (i + run < end && run < 65535 && (pixels[i + run].toInt() and 0x0F) == c) run++
            when {
                run <= 15 -> out.u8((run shl 4) or c)
                run <= 255 -> out.u8(c).u8(run)
                else -> out.u8(c).u8(0).u16le(run)
            }
            i += run
        }
    }

    /** RLE of a rectangle taken from a full-width level buffer of [stride] pixels per row. */
    fun rleRect(levels: ByteArray, stride: Int, x: Int, y: Int, w: Int, h: Int, out: ByteWriter) {
        // Runs may cross rows, so gather the rectangle's pixels in raster order first.
        val tmp = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(levels, (y + row) * stride + x, tmp, row * w, w)
        rle(tmp, 0, tmp.size, out)
    }

    /** Opaque rectangle write (draw op 1). Uses the compact form when the rect is aligned to 4×2. */
    fun bbox(levels: ByteArray, stride: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteWriter(64 + w * h / 4)
        out.u8(OP_BBOX).u8(0)
        val compact = x % 4 == 0 && w % 4 == 0 && y % 2 == 0 && h % 2 == 0 && x / 4 < 256 && w / 4 < 256 && y / 2 < 256 && h / 2 < 256
        if (compact) {
            out.u8(0).u8(x / 4).u8(y / 2).u8(w / 4).u8(h / 2)
        } else {
            out.u8(1).u16le(x).u16le(y).u16le(w).u16le(h)
        }
        rleRect(levels, stride, x, y, w, h, out)
        return out.toByteArray()
    }

    /** Fills the whole target with [level] (draw op 9). */
    fun clear(level: Int): ByteArray = byteArrayOf(OP_CLEAR.toByte(), 0, level.toByte())

    /** `[count u16] { [len u16] [call] }`. */
    fun sequence(calls: List<ByteArray>): ByteArray {
        val out = ByteWriter(calls.sumOf { it.size + 2 } + 2)
        out.u16le(calls.size)
        for (c in calls) out.u16le(c.size).bytes(c)
        return out.toByteArray()
    }

    /** Splits calls into DRAW_CALLS messages that respect the 65,535-byte and 4096-call limits. */
    fun drawMessages(calls: List<ByteArray>): List<ByteArray> {
        val messages = ArrayList<ByteArray>()
        var batch = ArrayList<ByteArray>()
        var size = 3
        for (c in calls) {
            require(c.size + 2 + 3 <= CfwEncoder.MAX_MESSAGE) { "draw call too large" }
            if (batch.isNotEmpty() && (size + c.size + 2 > CfwEncoder.MAX_MESSAGE || batch.size >= MAX_CALLS)) {
                messages += byteArrayOf(CfwType.DRAW_CALLS.toByte()) + sequence(batch)
                batch = ArrayList()
                size = 3
            }
            batch += c
            size += c.size + 2
        }
        if (batch.isNotEmpty()) messages += byteArrayOf(CfwType.DRAW_CALLS.toByte()) + sequence(batch)
        return messages
    }

    fun present(): ByteArray = byteArrayOf(CfwType.PRESENT.toByte())
    fun cleanup(): ByteArray = byteArrayOf(CfwType.CLEANUP.toByte())
    fun clearRoot(): ByteArray = byteArrayOf(CfwType.SET_ROOT.toByte(), 0xFF.toByte(), 0xFF.toByte())

    /** Brightness 2..100; visible=false fades to black. Fade ≤ 3000 ms. */
    fun brightness(level: Int, visible: Boolean, fadeMs: Int): ByteArray =
        ByteWriter(6).u8(CfwType.BRIGHTNESS).u8(1).u8(level.coerceIn(2, 100)).u8(if (visible) 1 else 0).u16le(fadeMs.coerceIn(0, 3000)).toByteArray()

    fun compass(start: Boolean): ByteArray = byteArrayOf(CfwType.COMPASS.toByte(), if (start) 1 else 0)
    fun ringBatteryQuery(): ByteArray = byteArrayOf(CfwType.RING_BATTERY.toByte(), 0)

    /** Buzzer tone sequence: up to 48 steps of (frequency Hz, duty 0..100, duration ms); duty 0 = rest. */
    fun toneSequence(steps: List<Tone>): ByteArray {
        val s = steps.take(48)
        val out = ByteWriter(3 + s.size * 5).u8(CfwType.BUZZER).u8(4).u8(s.size)
        for (t in s) out.u16le(t.frequencyHz).u8(t.duty).u16le(t.durationMs)
        return out.toByteArray()
    }

    data class Tone(val frequencyHz: Int, val duty: Int, val durationMs: Int)
}
