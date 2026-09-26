package com.madtreasures.faceclaw.core.ui.widgets

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.ui.Theme
import kotlin.math.roundToInt

/** Small reusable drawing helpers that follow the theme. */
object Drawing {
    /** Battery glyph: outline, nub and a fill proportional to [percent]. */
    fun battery(g: Canvas, x: Float, y: Float, percent: Int?, charging: Boolean, level: Int, w: Float = 24f, h: Float = 12f) {
        g.strokeRoundRect(x, y, w, h, 3f, 1.5f, level)
        g.fillRoundRect(x + w + 1f, y + h * 0.3f, 2.5f, h * 0.4f, 1f, level)
        val p = (percent ?: 0).coerceIn(0, 100)
        val fw = ((w - 5f) * p / 100f)
        if (fw > 0.5f) g.fillRoundRect(x + 2.5f, y + 2.5f, fw, h - 5f, 1.5f, level)
        if (charging) {
            // lightning bolt
            val cx = x + w / 2
            val cy = y + h / 2
            g.fillPolygon(
                floatArrayOf(cx + 1.5f, cx - 3.5f, cx - 0.5f, cx - 1.5f, cx + 3.5f, cx + 0.5f),
                floatArrayOf(cy - 6f, cy + 1f, cy + 1f, cy + 6f, cy - 1f, cy - 1f),
                0,
            )
        }
    }

    /** Horizontal progress bar with rounded ends. */
    fun progressBar(g: Canvas, r: IntRect, progress: Float, theme: Theme, level: Int = theme.levels.text) {
        val h = r.height.toFloat()
        g.fillRoundRect(r.left.toFloat(), r.top.toFloat(), r.width.toFloat(), h, h / 2, theme.levels.surfaceStrong)
        val w = (r.width * progress.coerceIn(0f, 1f))
        if (w >= 1f) g.fillRoundRect(r.left.toFloat(), r.top.toFloat(), maxOf(w, h), h, h / 2, level)
    }

    /** Page dots, e.g. for card carousels. */
    fun dots(g: Canvas, cx: Int, y: Int, count: Int, active: Int, theme: Theme) {
        if (count <= 1) return
        val gap = 14
        val start = cx - (count - 1) * gap / 2
        for (i in 0 until count) {
            val x = (start + i * gap).toFloat()
            if (i == active) g.fillCircle(x, y.toFloat(), 4f, theme.levels.text)
            else g.fillCircle(x, y.toFloat(), 3f, theme.levels.textFaint)
        }
    }

    fun formatDuration(ms: Long, showHours: Boolean = false): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0 || showHours) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun percentLevel(fraction: Float, theme: Theme): Int =
        (theme.levels.textFaint + (theme.levels.textStrong - theme.levels.textFaint) * fraction.coerceIn(0f, 1f)).roundToInt()
}
