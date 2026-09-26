package com.madtreasures.faceclaw.core.gfx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software rasteriser that draws into a [GrayBitmap].
 *
 * Levels are brightness values 0..255 (0 = black = see-through on the glasses). Shapes are
 * anti-aliased using signed distance functions; all drawing is source-over blending of a
 * solid level with the computed coverage, scaled by the current opacity.
 *
 * The canvas keeps a small state stack (translation, clip, opacity) like most 2D APIs.
 */
class Canvas(val target: GrayBitmap) {
    private class State(val tx: Float, val ty: Float, val clip: IntRect, val opacity: Int)

    private var tx = 0f
    private var ty = 0f
    private var clip: IntRect = target.bounds
    private var opacity = 255
    private val stack = ArrayList<State>()
    private val px = target.pixels
    private val stride = target.width

    val width: Int get() = target.width
    val height: Int get() = target.height

    /** The current clip in local (translated) coordinates. */
    val clipBounds: IntRect get() = clip.offset(-tx.roundToInt(), -ty.roundToInt())

    fun save() {
        stack += State(tx, ty, clip, opacity)
    }

    fun restore() {
        val s = stack.removeAt(stack.size - 1)
        tx = s.tx
        ty = s.ty
        clip = s.clip
        opacity = s.opacity
    }

    inline fun <T> withSave(block: Canvas.() -> T): T {
        save()
        try {
            return block()
        } finally {
            restore()
        }
    }

    fun translate(dx: Float, dy: Float) {
        tx += dx
        ty += dy
    }

    fun translate(dx: Int, dy: Int) = translate(dx.toFloat(), dy.toFloat())

    /** Intersects the clip with [r] (local coordinates). */
    fun clipRect(r: IntRect) {
        clip = clip.intersect(r.offset(tx.roundToInt(), ty.roundToInt()))
    }

    /** Multiplies the current opacity by [factor] (0..1). */
    fun multiplyOpacity(factor: Float) {
        opacity = (opacity * factor.coerceIn(0f, 1f)).roundToInt()
    }

    // ---------------------------------------------------------------- blending

    private fun blend(idx: Int, level: Int, coverage: Int) {
        val a = coverage * opacity / 255
        if (a <= 0) return
        if (a >= 255) {
            px[idx] = level.toByte()
            return
        }
        val dst = px[idx].toInt() and 0xFF
        px[idx] = ((dst * (255 - a) + level * a + 127) / 255).toByte()
    }

    private fun blendSpan(y: Int, x0: Int, x1: Int, level: Int) {
        if (x1 <= x0) return
        val row = y * stride
        if (opacity >= 255) {
            java.util.Arrays.fill(px, row + x0, row + x1, level.toByte())
        } else {
            for (x in x0 until x1) blend(row + x, level, 255)
        }
    }

    // ---------------------------------------------------------------- rectangles

    fun clear(level: Int = 0) {
        val c = clip
        for (y in c.top until c.bottom) java.util.Arrays.fill(px, y * stride + c.left, y * stride + c.right, level.coerceIn(0, 255).toByte())
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, level: Int) {
        val ox = tx.roundToInt()
        val oy = ty.roundToInt()
        val r = IntRect(x + ox, y + oy, x + ox + w, y + oy + h).intersect(clip)
        if (r.isEmpty) return
        val l = level.coerceIn(0, 255)
        for (yy in r.top until r.bottom) blendSpan(yy, r.left, r.right, l)
    }

    fun fillRect(r: IntRect, level: Int) = fillRect(r.left, r.top, r.width, r.height, level)

    fun strokeRect(r: IntRect, level: Int, thickness: Int = 1) {
        if (r.isEmpty) return
        val t = min(thickness, min(r.width, r.height) / 2).coerceAtLeast(1)
        fillRect(r.left, r.top, r.width, t, level)
        fillRect(r.left, r.bottom - t, r.width, t, level)
        fillRect(r.left, r.top + t, t, r.height - 2 * t, level)
        fillRect(r.right - t, r.top + t, t, r.height - 2 * t, level)
    }

    fun hline(x0: Int, x1: Int, y: Int, level: Int, thickness: Int = 1) = fillRect(min(x0, x1), y, abs(x1 - x0), thickness, level)
    fun vline(x: Int, y0: Int, y1: Int, level: Int, thickness: Int = 1) = fillRect(x, min(y0, y1), thickness, abs(y1 - y0), level)

    /** Scales the brightness of everything inside [r] by [factor], e.g. behind a modal. */
    fun dimRect(r: IntRect, factor: Float) {
        val dr = r.offset(tx.roundToInt(), ty.roundToInt()).intersect(clip)
        val f = (factor.coerceIn(0f, 1f) * 256).roundToInt()
        for (y in dr.top until dr.bottom) {
            val row = y * stride
            for (x in dr.left until dr.right) {
                px[row + x] = (((px[row + x].toInt() and 0xFF) * f) shr 8).toByte()
            }
        }
    }

    // ---------------------------------------------------------------- SDF shapes

    /**
     * Rasterises an arbitrary shape given by a signed distance function in device space over
     * the (device-space) bounding box. Negative distance = inside.
     */
    private inline fun rasterSdf(minX: Float, minY: Float, maxX: Float, maxY: Float, level: Int, sdf: (Float, Float) -> Float) {
        val x0 = max(floor(minX).toInt(), clip.left)
        val y0 = max(floor(minY).toInt(), clip.top)
        val x1 = min(ceil(maxX).toInt(), clip.right)
        val y1 = min(ceil(maxY).toInt(), clip.bottom)
        val l = level.coerceIn(0, 255)
        for (y in y0 until y1) {
            val cy = y + 0.5f
            val row = y * stride
            for (x in x0 until x1) {
                val d = sdf(x + 0.5f, cy)
                if (d >= 0.5f) continue
                val cov = if (d <= -0.5f) 255 else ((0.5f - d) * 255f).roundToInt()
                blend(row + x, l, cov)
            }
        }
    }

    private fun roundRectSdf(px0: Float, py0: Float, cx: Float, cy: Float, hw: Float, hh: Float, r: Float): Float {
        val qx = abs(px0 - cx) - (hw - r)
        val qy = abs(py0 - cy) - (hh - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
    }

    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, level: Int) {
        if (w <= 0f || h <= 0f) return
        val left = x + tx
        val top = y + ty
        val r = radius.coerceIn(0f, min(w, h) / 2f)
        val cx = left + w / 2f
        val cy = top + h / 2f
        val hw = w / 2f
        val hh = h / 2f
        // Rows outside the corner bands are spans with anti-aliased ends only.
        val bandTop = top + r
        val bandBottom = top + h - r
        val y0 = max(floor(top).toInt(), clip.top)
        val y1 = min(ceil(top + h).toInt(), clip.bottom)
        val l = level.coerceIn(0, 255)
        for (yy in y0 until y1) {
            val pcy = yy + 0.5f
            val fullRow = yy >= top && yy + 1 <= top + h && pcy - 0.5f >= bandTop && pcy + 0.5f <= bandBottom
            if (fullRow) {
                val inL = max(ceil(left).toInt(), clip.left)
                val inR = min(floor(left + w).toInt(), clip.right)
                blendSpan(yy, inL, inR, l)
                // anti-aliased edge pixels
                val el = floor(left).toInt()
                if (el < ceil(left).toInt() && el >= clip.left && el < clip.right) blend(yy * stride + el, l, ((ceil(left) - left) * 255).roundToInt())
                val er = floor(left + w).toInt()
                if (er < ceil(left + w).toInt() && er >= clip.left && er < clip.right) blend(yy * stride + er, l, ((left + w - er) * 255).roundToInt())
            } else {
                val xs = max(floor(left).toInt(), clip.left)
                val xe = min(ceil(left + w).toInt(), clip.right)
                val row = yy * stride
                for (xx in xs until xe) {
                    val d = roundRectSdf(xx + 0.5f, pcy, cx, cy, hw, hh, r)
                    if (d >= 0.5f) continue
                    val cov = if (d <= -0.5f) 255 else ((0.5f - d) * 255f).roundToInt()
                    blend(row + xx, l, cov)
                }
            }
        }
    }

    fun fillRoundRect(r: IntRect, radius: Float, level: Int) =
        fillRoundRect(r.left.toFloat(), r.top.toFloat(), r.width.toFloat(), r.height.toFloat(), radius, level)

    /** Strokes the inside edge of a rounded rectangle with the given [thickness]. */
    fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, thickness: Float, level: Int) {
        if (w <= 0f || h <= 0f) return
        val left = x + tx
        val top = y + ty
        val r = radius.coerceIn(0f, min(w, h) / 2f)
        val cx = left + w / 2f
        val cy = top + h / 2f
        val hw = w / 2f
        val hh = h / 2f
        val t = thickness
        rasterSdf(left, top, left + w, top + h, level) { px0, py0 ->
            val d = roundRectSdf(px0, py0, cx, cy, hw, hh, r)
            abs(d + t / 2f) - t / 2f
        }
    }

    fun strokeRoundRect(r: IntRect, radius: Float, thickness: Float, level: Int) =
        strokeRoundRect(r.left.toFloat(), r.top.toFloat(), r.width.toFloat(), r.height.toFloat(), radius, thickness, level)

    fun fillCircle(cx: Float, cy: Float, radius: Float, level: Int) {
        val dx = cx + tx
        val dy = cy + ty
        rasterSdf(dx - radius - 1, dy - radius - 1, dx + radius + 1, dy + radius + 1, level) { x, y ->
            hypot(x - dx, y - dy) - radius
        }
    }

    /** Strokes a circle whose stroke is centred on [radius]. */
    fun strokeCircle(cx: Float, cy: Float, radius: Float, thickness: Float, level: Int) {
        val dx = cx + tx
        val dy = cy + ty
        val outer = radius + thickness / 2f + 1
        rasterSdf(dx - outer, dy - outer, dx + outer, dy + outer, level) { x, y ->
            abs(hypot(x - dx, y - dy) - radius) - thickness / 2f
        }
    }

    /** Draws a line segment with round caps. */
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, level: Int) {
        val ax = x0 + tx
        val ay = y0 + ty
        val bx = x1 + tx
        val by = y1 + ty
        val hr = thickness / 2f
        val bax = bx - ax
        val bay = by - ay
        val len2 = bax * bax + bay * bay
        rasterSdf(min(ax, bx) - hr - 1, min(ay, by) - hr - 1, max(ax, bx) + hr + 1, max(ay, by) + hr + 1, level) { x, y ->
            val pax = x - ax
            val pay = y - ay
            val h = if (len2 == 0f) 0f else ((pax * bax + pay * bay) / len2).coerceIn(0f, 1f)
            hypot(pax - bax * h, pay - bay * h) - hr
        }
    }

    /**
     * Draws an arc with round caps, centred stroke. Angles are in degrees, 0 = 12 o'clock,
     * increasing clockwise (the natural orientation for dials and progress rings).
     */
    fun drawArc(cx: Float, cy: Float, radius: Float, thickness: Float, startDeg: Float, sweepDeg: Float, level: Int) {
        if (sweepDeg == 0f) return
        if (abs(sweepDeg) >= 360f) {
            strokeCircle(cx, cy, radius, thickness, level)
            return
        }
        val dx = cx + tx
        val dy = cy + ty
        val start = if (sweepDeg >= 0) startDeg else startDeg + sweepDeg
        val sweep = abs(sweepDeg)
        val hr = thickness / 2f
        val s0 = Math.toRadians(start.toDouble())
        val s1 = Math.toRadians((start + sweep).toDouble())
        val e0x = dx + radius * sin(s0).toFloat()
        val e0y = dy - radius * cos(s0).toFloat()
        val e1x = dx + radius * sin(s1).toFloat()
        val e1y = dy - radius * cos(s1).toFloat()
        val outer = radius + hr + 1
        rasterSdf(dx - outer, dy - outer, dx + outer, dy + outer, level) { x, y ->
            var a = Math.toDegrees(atan2((x - dx).toDouble(), (dy - y).toDouble())).toFloat()
            if (a < 0) a += 360f
            var rel = (a - start) % 360f
            if (rel < 0) rel += 360f
            if (rel <= sweep) {
                abs(hypot(x - dx, y - dy) - radius) - hr
            } else {
                min(hypot(x - e0x, y - e0y), hypot(x - e1x, y - e1y)) - hr
            }
        }
    }

    /** Fills a simple (non-self-intersecting) polygon with anti-aliased edges. */
    fun fillPolygon(xs: FloatArray, ys: FloatArray, level: Int) {
        val n = xs.size
        if (n < 3 || ys.size != n) return
        val vx = FloatArray(n) { xs[it] + tx }
        val vy = FloatArray(n) { ys[it] + ty }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, vx[i]); maxX = max(maxX, vx[i])
            minY = min(minY, vy[i]); maxY = max(maxY, vy[i])
        }
        rasterSdf(minX - 1, minY - 1, maxX + 1, maxY + 1, level) { x, y -> polygonSdf(vx, vy, x, y) }
    }

    private fun polygonSdf(vx: FloatArray, vy: FloatArray, x: Float, y: Float): Float {
        val n = vx.size
        var d = (x - vx[0]) * (x - vx[0]) + (y - vy[0]) * (y - vy[0])
        var s = 1f
        var j = n - 1
        for (i in 0 until n) {
            val ex = vx[j] - vx[i]
            val ey = vy[j] - vy[i]
            val wx = x - vx[i]
            val wy = y - vy[i]
            val len2 = ex * ex + ey * ey
            val h = if (len2 == 0f) 0f else ((wx * ex + wy * ey) / len2).coerceIn(0f, 1f)
            val bx = wx - ex * h
            val by = wy - ey * h
            d = min(d, bx * bx + by * by)
            val c1 = y >= vy[i]
            val c2 = y < vy[j]
            val c3 = ex * wy > ey * wx
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
            j = i
        }
        return s * sqrt(d)
    }

    /** Regular polygon helper (triangles for play buttons, arrows, compass needles...). */
    fun fillRegularPolygon(cx: Float, cy: Float, radius: Float, sides: Int, rotationDeg: Float, level: Int) {
        val xs = FloatArray(sides)
        val ys = FloatArray(sides)
        for (i in 0 until sides) {
            val a = Math.toRadians((rotationDeg + i * 360.0 / sides))
            xs[i] = cx + radius * sin(a).toFloat()
            ys[i] = cy - radius * cos(a).toFloat()
        }
        fillPolygon(xs, ys, level)
    }

    // ---------------------------------------------------------------- bitmaps & text

    /** Blends a coverage mask ([w]x[h] bytes) at integer position using [level]. */
    fun drawMask(mask: ByteArray, w: Int, h: Int, x: Int, y: Int, level: Int) {
        val dx = x + tx.roundToInt()
        val dy = y + ty.roundToInt()
        val r = IntRect(dx, dy, dx + w, dy + h).intersect(clip)
        if (r.isEmpty) return
        val l = level.coerceIn(0, 255)
        for (yy in r.top until r.bottom) {
            val mrow = (yy - dy) * w
            val row = yy * stride
            for (xx in r.left until r.right) {
                val c = mask[mrow + xx - dx].toInt() and 0xFF
                if (c != 0) blend(row + xx, l, c)
            }
        }
    }

    /** Draws a greyscale image; black pixels stay transparent when [transparentBlack] is set. */
    fun drawImage(img: GrayBitmap, x: Int, y: Int, opacity: Float = 1f, transparentBlack: Boolean = false) {
        val dx = x + tx.roundToInt()
        val dy = y + ty.roundToInt()
        val r = IntRect(dx, dy, dx + img.width, dy + img.height).intersect(clip)
        if (r.isEmpty) return
        val o = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
        for (yy in r.top until r.bottom) {
            val srow = (yy - dy) * img.width
            val row = yy * stride
            for (xx in r.left until r.right) {
                val v = img.pixels[srow + xx - dx].toInt() and 0xFF
                if (transparentBlack && v == 0) continue
                blend(row + xx, v, o)
            }
        }
    }

    /** Draws [text] with its baseline at [baselineY]; returns the advance in pixels. */
    fun drawText(text: CharSequence, x: Float, baselineY: Float, font: BitmapFont, level: Int): Float {
        var penQ4 = ((x + tx) * 16f).roundToInt()
        val by = (baselineY + ty).roundToInt()
        val startQ4 = penQ4
        var prev = -1
        var i = 0
        val l = level.coerceIn(0, 255)
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (prev >= 0) penQ4 += font.kernQ4(prev, cp)
            val g = font.glyphOrFallback(cp)
            if (g != null) {
                if (g.width > 0 && g.height > 0) {
                    val gx = ((penQ4 + 8) shr 4) + g.bearingX
                    val gy = by + g.bearingY
                    blitGlyph(g, gx, gy, l)
                }
                penQ4 += g.advanceQ4
            }
            prev = cp
        }
        return (penQ4 - startQ4) / 16f
    }

    private fun blitGlyph(g: Glyph, gx: Int, gy: Int, level: Int) {
        val r = IntRect(gx, gy, gx + g.width, gy + g.height).intersect(clip)
        if (r.isEmpty) return
        for (yy in r.top until r.bottom) {
            val mrow = (yy - gy) * g.width
            val row = yy * stride
            for (xx in r.left until r.right) {
                val c = g.alpha[mrow + xx - gx].toInt() and 0xFF
                if (c != 0) blend(row + xx, level, c)
            }
        }
    }

    /** Draws a single glyph (e.g. an icon from an icon font) centred in the square at ([x],[y]) of [size]. */
    fun drawIcon(codepoint: Int, x: Float, y: Float, size: Int, font: BitmapFont, level: Int) {
        val g = font.glyph(codepoint) ?: return
        val gx = (x + tx + (size - g.width) / 2f).roundToInt()
        val gy = (y + ty + (size - g.height) / 2f).roundToInt()
        blitGlyph(g, gx, gy, level.coerceIn(0, 255))
    }

    /**
     * Draws a single line of text inside [rect] with the given alignment. The line is
     * ellipsised when it does not fit. Vertical centring uses the cap height so text looks
     * optically centred.
     */
    fun drawTextIn(
        text: String,
        rect: IntRect,
        font: BitmapFont,
        level: Int,
        hAlign: HAlign = HAlign.Start,
        vAlign: VAlign = VAlign.Center,
        ellipsize: Boolean = true,
    ): Float {
        val s = if (ellipsize) TextLayout.ellipsize(font, text, rect.width) else text
        val w = TextLayout.widthQ4(font, s) / 16f
        val x = when (hAlign) {
            HAlign.Start -> rect.left.toFloat()
            HAlign.Center -> rect.left + (rect.width - w) / 2f
            HAlign.End -> rect.right - w
        }
        val baseline = when (vAlign) {
            VAlign.Top -> rect.top + font.ascent.toFloat()
            VAlign.Center -> rect.top + (rect.height + font.capHeight) / 2f
            VAlign.Bottom -> rect.bottom - font.descent.toFloat()
        }
        return drawText(s, floor(x), floor(baseline), font, level)
    }

    companion object {
        const val TAU = (2 * PI).toFloat()
    }
}
