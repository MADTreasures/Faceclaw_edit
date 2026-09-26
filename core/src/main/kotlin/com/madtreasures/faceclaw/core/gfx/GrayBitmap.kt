package com.madtreasures.faceclaw.core.gfx

/**
 * An 8-bit greyscale image. 0 is black (fully transparent on the glasses' additive
 * display), 255 is full brightness. Row-major, no padding.
 */
class GrayBitmap(val width: Int, val height: Int, val pixels: ByteArray = ByteArray(width * height)) {
    init {
        require(width >= 0 && height >= 0) { "negative size" }
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    val bounds: IntRect get() = IntRect(0, 0, width, height)

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    fun fill(value: Int) {
        pixels.fill(value.coerceIn(0, 255).toByte())
    }

    fun copy(): GrayBitmap = GrayBitmap(width, height, pixels.copyOf())

    fun copyFrom(other: GrayBitmap) {
        require(other.width == width && other.height == height)
        System.arraycopy(other.pixels, 0, pixels, 0, pixels.size)
    }

    fun crop(rect: IntRect): GrayBitmap {
        val r = rect.intersect(bounds)
        val out = GrayBitmap(r.width, r.height)
        for (y in 0 until r.height) {
            System.arraycopy(pixels, (r.top + y) * width + r.left, out.pixels, y * r.width, r.width)
        }
        return out
    }

    /** True when every pixel equals the one in [other]. */
    fun contentEquals(other: GrayBitmap): Boolean =
        width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    /** Bounding box of pixels that differ from [other], or [IntRect.EMPTY]. */
    fun diffBounds(other: GrayBitmap): IntRect {
        require(other.width == width && other.height == height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        val a = pixels
        val b = other.pixels
        for (y in 0 until height) {
            val row = y * width
            var x0 = -1
            for (x in 0 until width) {
                if (a[row + x] != b[row + x]) {
                    x0 = x
                    break
                }
            }
            if (x0 < 0) continue
            var x1 = x0
            for (x in width - 1 downTo x0) {
                if (a[row + x] != b[row + x]) {
                    x1 = x
                    break
                }
            }
            if (x0 < minX) minX = x0
            if (x1 > maxX) maxX = x1
            if (y < minY) minY = y
            maxY = y
        }
        return if (maxX < 0) IntRect.EMPTY else IntRect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Nearest-neighbour or box-filtered rescale, good enough for thumbnails and album art. */
    fun scaled(newWidth: Int, newHeight: Int): GrayBitmap {
        val out = GrayBitmap(newWidth, newHeight)
        if (width == 0 || height == 0) return out
        for (y in 0 until newHeight) {
            val sy0 = y * height / newHeight
            val sy1 = maxOf(sy0 + 1, (y + 1) * height / newHeight)
            for (x in 0 until newWidth) {
                val sx0 = x * width / newWidth
                val sx1 = maxOf(sx0 + 1, (x + 1) * width / newWidth)
                var sum = 0
                var n = 0
                for (sy in sy0 until minOf(sy1, height)) {
                    for (sx in sx0 until minOf(sx1, width)) {
                        sum += this[sx, sy]
                        n++
                    }
                }
                out.pixels[y * newWidth + x] = (if (n == 0) 0 else sum / n).toByte()
            }
        }
        return out
    }

    companion object {
        /** Builds a bitmap from RGB(A) pixels using perceptual luminance. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayBitmap {
            val out = GrayBitmap(width, height)
            for (i in argb.indices) {
                val c = argb[i]
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r * 54 + g * 183 + b * 19) shr 8
                out.pixels[i] = (lum * a / 255).toByte()
            }
            return out
        }
    }
}
