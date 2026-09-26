package com.madtreasures.faceclaw.core.protocol

import com.madtreasures.faceclaw.core.gfx.GrayBitmap

/**
 * Turns rendered 8-bit frames into draw calls for the custom firmware's 640×480 4-bit screen
 * buffer: quantise, diff against what was last sent, and emit one RLE rectangle per changed
 * 32-row band (aligned to the compact 4×2 grid).
 */
object FrameEncoder {
    const val WIDTH = 640
    const val HEIGHT = 480
    const val BAND = 32

    /** 8-bit → 4-bit level, identical to the firmware-side expectation: min(15, (v+8)>>4). */
    fun level(v: Int): Int = minOf(15, (v + 8) shr 4)

    private val lut = ByteArray(256) { level(it).toByte() }

    fun quantize(bmp: GrayBitmap): ByteArray {
        require(bmp.width == WIDTH && bmp.height == HEIGHT) { "frames must be ${WIDTH}x$HEIGHT" }
        val out = ByteArray(WIDTH * HEIGHT)
        val src = bmp.pixels
        for (i in out.indices) out[i] = lut[src[i].toInt() and 0xFF]
        return out
    }

    /** Full-screen rewrite: one rectangle per band. */
    fun keyframe(levels: ByteArray): List<ByteArray> =
        (0 until HEIGHT / BAND).map { b -> CfwDraw.bbox(levels, WIDTH, 0, b * BAND, WIDTH, BAND) }

    /** Rectangles covering every pixel that differs between [prev] and [next]. */
    fun diff(prev: ByteArray, next: ByteArray): List<ByteArray> {
        val calls = ArrayList<ByteArray>()
        for (b in 0 until HEIGHT / BAND) {
            var minX = WIDTH
            var maxX = -1
            var minY = HEIGHT
            var maxY = -1
            for (y in b * BAND until (b + 1) * BAND) {
                val row = y * WIDTH
                var x0 = -1
                for (x in 0 until WIDTH) if (prev[row + x] != next[row + x]) { x0 = x; break }
                if (x0 < 0) continue
                var x1 = x0
                for (x in WIDTH - 1 downTo x0) if (prev[row + x] != next[row + x]) { x1 = x; break }
                if (x0 < minX) minX = x0
                if (x1 > maxX) maxX = x1
                if (y < minY) minY = y
                maxY = y
            }
            if (maxX < 0) continue
            val x = minX / 4 * 4
            val w = ((maxX + 1 - x + 3) / 4 * 4).coerceAtMost(WIDTH - x)
            val y = minY / 2 * 2
            val h = ((maxY + 1 - y + 1) / 2 * 2).coerceAtMost(HEIGHT - y)
            calls += CfwDraw.bbox(next, WIDTH, x, y, w, h)
        }
        return calls
    }
}
