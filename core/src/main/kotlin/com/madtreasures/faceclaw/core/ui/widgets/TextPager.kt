package com.madtreasures.faceclaw.core.ui.widgets

import com.madtreasures.faceclaw.core.gfx.BitmapFont
import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.Easing
import com.madtreasures.faceclaw.core.ui.Theme
import com.madtreasures.faceclaw.core.ui.Ui
import kotlin.math.max
import kotlin.math.roundToInt

/** Word-wrapped, scrollable block of text (notification bodies, answers, teleprompter). */
class TextPager(
    text: String,
    private val font: (Theme) -> BitmapFont = { it.type.body },
    private val lineSpacing: Int = 6,
    private val level: (Theme) -> Int = { it.levels.text },
) {
    var text: String = text
        private set
    private var lines: List<String> = emptyList()
    private var wrappedFor = -1
    private var wrappedFont: BitmapFont? = null
    private val scroll = AnimatedFloat(0f)
    private var viewport = 0
    private var contentHeight = 0

    val maxScroll: Float get() = max(0, contentHeight - viewport).toFloat()
    val scrollTarget: Float get() = scroll.target
    val atEnd: Boolean get() = scroll.target >= maxScroll - 0.5f
    val atStart: Boolean get() = scroll.target <= 0.5f

    fun setText(t: String, keepScroll: Boolean = false) {
        text = t
        wrappedFor = -1
        if (!keepScroll) scroll.snapTo(0f)
    }

    private fun ensureWrapped(width: Int, theme: Theme) {
        val f = font(theme)
        if (width == wrappedFor && f == wrappedFont) return
        lines = TextLayout.wrap(f, text, width)
        wrappedFor = width
        wrappedFont = f
        contentHeight = lines.size * (f.lineHeight + lineSpacing)
    }

    fun lineCount(width: Int, theme: Theme): Int {
        ensureWrapped(width, theme)
        return lines.size
    }

    fun render(g: Canvas, bounds: IntRect, ui: Ui) {
        val theme = ui.theme
        ensureWrapped(bounds.width - 12, theme)
        viewport = bounds.height
        val f = font(theme)
        val lh = f.lineHeight + lineSpacing
        val off = scroll.value(ui.nowMs).coerceIn(0f, maxScroll)
        g.withSave {
            clipRect(bounds)
            val first = max(0, (off / lh).toInt())
            var i = first
            while (i < lines.size) {
                val y = bounds.top - off + i * lh
                if (y > bounds.bottom) break
                drawText(lines[i], bounds.left.toFloat(), (y + f.ascent).roundToInt().toFloat(), f, level(theme))
                i++
            }
        }
        if (contentHeight > viewport) {
            val trackH = bounds.height - 8f
            val thumbH = max(20f, trackH * viewport / contentHeight)
            val y = bounds.top + 4f + (trackH - thumbH) * (off / maxScroll)
            g.fillRoundRect((bounds.right - 3).toFloat(), bounds.top + 4f, 3f, trackH, 1.5f, theme.levels.divider)
            g.fillRoundRect((bounds.right - 3.5f), y, 4f, thumbH, 2f, theme.levels.textDim)
        }
    }

    /** Scrolls by a fraction of the viewport; returns false when already at that end. */
    fun page(direction: Int, ui: Ui, fraction: Float = 0.6f): Boolean {
        val step = max(1f, viewport * fraction)
        val target = (scroll.target + direction * step).coerceIn(0f, maxScroll)
        if (target == scroll.target) return false
        scroll.animateTo(target, ui.nowMs, if (ui.theme.motion.enabled) ui.theme.motion.normalMs else 0, Easing.OutCubic)
        ui.invalidate()
        return true
    }

    fun scrollTo(px: Float, ui: Ui, animate: Boolean = false) {
        val t = px.coerceIn(0f, maxScroll)
        if (animate) scroll.animateTo(t, ui.nowMs, ui.theme.motion.normalMs) else scroll.snapTo(t)
        ui.invalidate()
    }

    fun onAction(action: Action, ui: Ui): Boolean = when (action) {
        Action.Previous -> page(-1, ui)
        Action.Next -> page(1, ui)
        else -> false
    }

    fun isAnimating(nowMs: Long) = scroll.isRunning(nowMs)
}
