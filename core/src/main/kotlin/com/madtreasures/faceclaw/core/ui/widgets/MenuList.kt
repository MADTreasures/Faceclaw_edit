package com.madtreasures.faceclaw.core.ui.widgets

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.Easing
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.Theme
import com.madtreasures.faceclaw.core.ui.Ui
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vertical list with a gliding selection capsule. Handles Previous/Next/Select itself;
 * the owning screen forwards actions and draws it inside any rectangle.
 */
class MenuList(items: List<MenuItem> = emptyList()) {
    var items: List<MenuItem> = items
        private set
    var selectedIndex: Int = firstSelectable(items)
        private set
    /** True while a [MenuItem.Stepper] is being adjusted. */
    var editing: Boolean = false
        private set

    /** Called when the selection moves (e.g. to update a preview). */
    var onSelectionChanged: ((MenuItem?) -> Unit)? = null

    private val highlightTop = AnimatedFloat(0f)
    private val highlightHeight = AnimatedFloat(0f)
    private val scroll = AnimatedFloat(0f)
    private val bounce = AnimatedFloat(0f)
    private var viewportHeight = 0
    private var laidOut = false

    val selectedItem: MenuItem? get() = items.getOrNull(selectedIndex)?.takeIf { it.selectable }

    fun setItems(newItems: List<MenuItem>) {
        val key = selectedItem?.key
        items = newItems
        val kept = if (key != null) newItems.indexOfFirst { it.selectable && it.key == key } else -1
        selectedIndex = if (kept >= 0) kept else firstSelectable(newItems).coerceAtMost(max(0, newItems.size - 1))
        if (selectedItem !is MenuItem.Stepper) editing = false
        laidOut = false
    }

    fun select(index: Int) {
        if (index in items.indices && items[index].selectable) {
            selectedIndex = index
            laidOut = false
        }
    }

    // ------------------------------------------------------------------ layout

    fun rowHeight(item: MenuItem, theme: Theme): Int = when (item) {
        is MenuItem.Header -> 36
        is MenuItem.Custom -> item.height
        else -> if (item.subtitle != null) theme.spacing.rowHeight + 18 else theme.spacing.rowHeight
    }

    private fun tops(theme: Theme): IntArray {
        val t = IntArray(items.size + 1)
        for (i in items.indices) t[i + 1] = t[i] + rowHeight(items[i], theme)
        return t
    }

    private fun updateTargets(ui: Ui, animate: Boolean) {
        val theme = ui.theme
        val tops = tops(theme)
        val contentHeight = tops.last()
        val i = selectedIndex.coerceIn(0, max(0, items.size - 1))
        val top = if (items.isEmpty()) 0 else tops[i]
        val h = if (items.isEmpty()) 0 else rowHeight(items[i], theme)
        val margin = theme.spacing.rowHeight / 2
        var target = scroll.target
        if (i == firstSelectable(items)) target = 0f
        if (top - margin < target) target = (top - margin).toFloat()
        if (top + h + margin > target + viewportHeight) target = (top + h + margin - viewportHeight).toFloat()
        target = target.coerceIn(0f, max(0, contentHeight - viewportHeight).toFloat())
        val dur = if (animate && theme.motion.enabled) theme.motion.normalMs else 0L
        scroll.animateTo(target, ui.nowMs, dur)
        highlightTop.animateTo(top.toFloat(), ui.nowMs, dur)
        highlightHeight.animateTo(h.toFloat(), ui.nowMs, dur)
    }

    fun isAnimating(nowMs: Long): Boolean =
        scroll.isRunning(nowMs) || highlightTop.isRunning(nowMs) || highlightHeight.isRunning(nowMs) || bounce.isRunning(nowMs)

    // ------------------------------------------------------------------ input

    fun onAction(action: Action, ui: Ui): Boolean {
        if (editing) {
            val st = selectedItem as? MenuItem.Stepper ?: run { editing = false; return false }
            when (action) {
                Action.Previous -> st.set((st.get() + st.step).coerceIn(st.range))
                Action.Next -> st.set((st.get() - st.step).coerceIn(st.range))
                Action.Select, Action.Back -> editing = false
                else -> return false
            }
            ui.invalidate()
            return true
        }
        return when (action) {
            Action.Previous -> move(-1, ui)
            Action.Next -> move(1, ui)
            Action.Select -> activate(ui)
            else -> false
        }
    }

    private fun move(dir: Int, ui: Ui): Boolean {
        var i = selectedIndex + dir
        while (i in items.indices && !items[i].selectable) i += dir
        if (i !in items.indices) {
            if (ui.theme.motion.enabled) {
                bounce.snapTo(if (dir < 0) -10f else 10f)
                bounce.animateTo(0f, ui.nowMs, 260, Easing.OutBack)
            }
            ui.invalidate()
            return true
        }
        selectedIndex = i
        updateTargets(ui, animate = true)
        onSelectionChanged?.invoke(selectedItem)
        ui.invalidate()
        return true
    }

    private fun activate(ui: Ui): Boolean {
        when (val item = selectedItem ?: return false) {
            is MenuItem.Action -> item.onSelect()
            is MenuItem.Link -> ui.push(item.open())
            is MenuItem.Toggle -> item.set(!item.get())
            is MenuItem.Choice<*> -> if (item.options.size <= 4) item.cycle() else ui.push(ChoicePickerScreen(item))
            is MenuItem.Stepper -> editing = true
            is MenuItem.Custom -> item.onSelect()
            is MenuItem.Header, is MenuItem.Info -> return false
        }
        ui.invalidate()
        return true
    }

    // ------------------------------------------------------------------ drawing

    fun render(g: Canvas, bounds: IntRect, ui: Ui) {
        val theme = ui.theme
        val now = ui.nowMs
        if (viewportHeight != bounds.height || !laidOut) {
            viewportHeight = bounds.height
            updateTargets(ui, animate = laidOut)
            laidOut = true
        }
        val tops = tops(theme)
        val offset = scroll.value(now) + bounce.value(now)
        g.withSave {
            clipRect(bounds)
            val baseY = bounds.top - offset
            if (selectedItem != null) {
                val hy = baseY + highlightTop.value(now)
                val hh = highlightHeight.value(now)
                val r = min(hh / 2f, theme.shapes.radiusLarge)
                fillRoundRect(bounds.left.toFloat(), hy + 3, bounds.width.toFloat(), hh - 6, r, theme.levels.surface)
                strokeRoundRect(bounds.left.toFloat(), hy + 3, bounds.width.toFloat(), hh - 6, r,
                    if (editing) theme.shapes.stroke + 1 else theme.shapes.stroke, theme.levels.outline)
            }
            for (i in items.indices) {
                val top = (baseY + tops[i]).roundToInt()
                val h = tops[i + 1] - tops[i]
                if (top + h < bounds.top || top > bounds.bottom) continue
                drawRow(this, items[i], IntRect(bounds.left, top, bounds.right, top + h), i == selectedIndex, theme)
            }
        }
        drawScrollbar(g, bounds, tops.last(), offset, theme)
    }

    private fun drawRow(g: Canvas, item: MenuItem, row: IntRect, selected: Boolean, theme: Theme) {
        val lv = theme.levels
        val t = theme.type
        if (item is MenuItem.Custom) {
            item.draw(g, row, selected, theme)
            return
        }
        if (item is MenuItem.Header) {
            g.drawTextIn(item.label.uppercase(), row.inset(18, 0, 18, 0).dropTop(8), t.caption, lv.textFaint)
            return
        }
        val labelLevel = when {
            !item.selectable -> lv.textDim
            selected -> lv.textStrong
            else -> lv.textDim
        }
        var x = row.left + 18
        item.icon?.let { icon ->
            val size = theme.spacing.iconSize
            g.drawIcon(icon, x.toFloat(), (row.centerY - size / 2).toFloat(), size, t.icons(size), if (selected) lv.textStrong else lv.textDim)
            x += size + 14
        }
        // trailing element
        var right = row.right - 18
        when (item) {
            is MenuItem.Toggle -> {
                right = drawSwitch(g, right, row.centerY, item.get(), selected, theme) - 12
            }
            is MenuItem.Link -> {
                g.drawIcon(Icons.ChevronRight, (right - 24).toFloat(), (row.centerY - 12).toFloat(), 24, t.icons(24), if (selected) lv.text else lv.textFaint)
                right -= 30
                item.detail?.let { right = drawDetail(g, it, right, row, selected, theme) }
            }
            is MenuItem.Action -> {
                if (item.trailingDot) {
                    g.fillCircle((right - 5).toFloat(), row.centerY.toFloat(), 4f, if (selected) lv.textStrong else lv.textDim)
                    right -= 18
                }
                item.detail?.let { right = drawDetail(g, it, right, row, selected, theme) }
            }
            is MenuItem.Choice<*> -> right = drawDetail(g, item.currentLabel(), right, row, selected, theme)
            is MenuItem.Stepper -> {
                val value = item.format(item.get())
                if (editing && selected) {
                    val font = t.bodyStrong
                    val w = TextLayout.width(font, value)
                    g.drawIcon(Icons.ExpandLess, (right - w / 2 - 12).toFloat(), (row.top + 1).toFloat(), 24, t.icons(24), lv.textStrong)
                    g.drawText(value, (right - w).toFloat(), (row.centerY + font.capHeight / 2).toFloat(), font, lv.textStrong)
                    g.drawIcon(Icons.ExpandMore, (right - w / 2 - 12).toFloat(), (row.bottom - 25).toFloat(), 24, t.icons(24), lv.textStrong)
                    right -= w + 12
                } else {
                    right = drawDetail(g, value, right, row, selected, theme)
                }
            }
            is MenuItem.Info -> item.detail?.let { right = drawDetail(g, it, right, row, false, theme) }
            else -> {}
        }
        val textRect = IntRect(x, row.top, max(x, right), row.bottom)
        val sub = item.subtitle
        if (sub == null) {
            g.drawTextIn(item.label, textRect, if (selected) t.bodyStrong else t.body, labelLevel)
        } else {
            val labelFont = if (selected) t.bodyStrong else t.body
            val subFont = t.caption
            val total = labelFont.capHeight + 10 + subFont.lineHeight
            val top = row.centerY - total / 2
            g.drawText(TextLayout.ellipsize(labelFont, item.label, textRect.width), x.toFloat(), (top + labelFont.capHeight).toFloat(), labelFont, labelLevel)
            g.drawText(TextLayout.ellipsize(subFont, sub, textRect.width), x.toFloat(), (top + labelFont.capHeight + 10 + subFont.ascent).toFloat(), subFont, if (selected) lv.text else lv.textFaint)
        }
    }

    private fun drawDetail(g: Canvas, text: String, right: Int, row: IntRect, selected: Boolean, theme: Theme): Int {
        val font = theme.type.caption
        val maxW = row.width / 3
        val s = TextLayout.ellipsize(font, text, maxW)
        val w = TextLayout.width(font, s)
        g.drawTextIn(s, IntRect(right - w, row.top, right, row.bottom), font, if (selected) theme.levels.text else theme.levels.textFaint, HAlign.End)
        return right - w - 12
    }

    private fun drawSwitch(g: Canvas, right: Int, cy: Int, on: Boolean, selected: Boolean, theme: Theme): Int {
        val w = 46f
        val h = 26f
        val x = right - w
        val y = cy - h / 2
        val lv = theme.levels
        if (on) {
            g.fillRoundRect(x, y, w, h, h / 2, if (selected) lv.textStrong else lv.text)
            g.fillCircle(x + w - h / 2, cy.toFloat(), h / 2 - 4, 0)
        } else {
            g.strokeRoundRect(x, y, w, h, h / 2, 2f, if (selected) lv.text else lv.textFaint)
            g.fillCircle(x + h / 2, cy.toFloat(), h / 2 - 6, if (selected) lv.text else lv.textFaint)
        }
        return x.roundToInt()
    }

    private fun drawScrollbar(g: Canvas, bounds: IntRect, contentHeight: Int, offset: Float, theme: Theme) {
        if (contentHeight <= bounds.height || bounds.height <= 0) return
        val trackTop = bounds.top + 6f
        val trackH = bounds.height - 12f
        val thumbH = max(24f, trackH * bounds.height / contentHeight)
        val maxOffset = (contentHeight - bounds.height).toFloat()
        val thumbY = trackTop + (trackH - thumbH) * (offset / maxOffset).coerceIn(0f, 1f)
        val x = bounds.right + 6f
        g.fillRoundRect(x, trackTop, 3f, trackH, 1.5f, theme.levels.divider)
        g.fillRoundRect(x - 0.5f, thumbY, 4f, thumbH, 2f, theme.levels.textDim)
    }

    companion object {
        fun firstSelectable(items: List<MenuItem>): Int = items.indexOfFirst { it.selectable }.let { if (it < 0) 0 else it }
    }
}

/** Full-screen picker for [MenuItem.Choice] rows with many options. */
class ChoicePickerScreen<T>(private val choice: MenuItem.Choice<T>) : Screen() {
    private val list = MenuList()

    override val title: String get() = choice.label

    override fun onAttach() {
        list.setItems(choice.options.map { (value, label) ->
            MenuItem.Action(label, icon = if (value == choice.get()) Icons.Check else null, key = label) {
                choice.set(value)
                ui.pop()
            }
        })
        val current = choice.options.indexOfFirst { it.first == choice.get() }
        if (current >= 0) list.select(current)
    }

    override fun render(g: Canvas, bounds: IntRect) = list.render(g, bounds, ui)
    override fun onAction(action: Action): Boolean = list.onAction(action, ui)
    override fun isAnimating(nowMs: Long): Boolean = list.isAnimating(nowMs)
}
