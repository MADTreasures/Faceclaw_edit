package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.platform.PhoneNotification
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Theme
import com.madtreasures.faceclaw.core.ui.Ui
import com.madtreasures.faceclaw.core.ui.widgets.MenuList
import kotlin.math.min
import kotlin.math.roundToInt

/** Something drawn above the current screen: menus, dialogs, toasts, popups. */
abstract class Overlay {
    /** Modal overlays receive all input and dim what is behind them. */
    open val modal: Boolean get() = true
    open val keepAwake: Boolean get() = modal
    /** Wall-clock time at which the overlay disappears by itself. */
    var expiresAtMs: Long? = null
    internal var onDismissRequested: ((Overlay) -> Unit)? = null
    private val appear = AnimatedFloat(0f)
    private var appearStarted = false

    fun dismiss() {
        onDismissRequested?.invoke(this)
    }

    /** 0..1 fade-in progress. */
    protected fun appearProgress(ui: Ui): Float {
        if (!appearStarted) {
            appearStarted = true
            appear.snapTo(0f)
            appear.animateTo(1f, ui.nowMs, if (ui.theme.motion.enabled) ui.theme.motion.fastMs else 0)
        }
        return appear.value(ui.nowMs)
    }

    abstract fun render(g: Canvas, viewport: IntRect, ui: Ui)

    /** Return true when the action was consumed. */
    open fun onAction(action: Action, ui: Ui): Boolean = modal

    open fun isAnimating(nowMs: Long): Boolean = appear.isRunning(nowMs)

    companion object {
        /** Draws the standard panel and returns its inner content rectangle. */
        fun panel(g: Canvas, viewport: IntRect, contentHeight: Int, theme: Theme, alpha: Float, anchorBottom: Boolean = false): IntRect {
            val pad = theme.spacing.l
            val w = viewport.width - 2 * theme.spacing.xxl
            val h = min(contentHeight + 2 * pad, (viewport.height * 0.86f).roundToInt())
            val x = viewport.left + (viewport.width - w) / 2
            val slide = ((1f - alpha) * 16).roundToInt()
            val y = if (anchorBottom) viewport.bottom - h - theme.spacing.xl + slide else viewport.top + (viewport.height - h) / 2 + slide
            val r = IntRect.of(x, y, w, h)
            g.fillRoundRect(r, theme.shapes.radiusLarge, theme.levels.background)
            g.strokeRoundRect(r, theme.shapes.radiusLarge, theme.shapes.stroke, (theme.levels.outline * alpha).roundToInt())
            return r.inset(pad)
        }
    }
}

/** Long-press menu and other lists shown in a floating panel. */
class MenuOverlay(private val title: String?, items: List<MenuItem>) : Overlay() {
    private val list = MenuList(items)

    override fun render(g: Canvas, viewport: IntRect, ui: Ui) {
        val theme = ui.theme
        val a = appearProgress(ui)
        val titleH = if (title != null) theme.type.title.lineHeight + theme.spacing.s else 0
        val listH = list.items.sumOf { list.rowHeight(it, theme) }
        val inner = panel(g, viewport, titleH + listH, theme, a)
        if (title != null) {
            g.drawTextIn(title, inner.takeTop(theme.type.title.lineHeight), theme.type.title, theme.levels.text, HAlign.Start)
        }
        list.render(g, inner.dropTop(titleH).dropRight(10), ui)
    }

    override fun onAction(action: Action, ui: Ui): Boolean {
        when (action) {
            Action.Back, Action.Menu -> if (!list.editing) {
                dismiss()
                return true
            }
            Action.Select -> {
                val item = list.selectedItem
                if (item is MenuItem.Action || item is MenuItem.Link) dismiss()
            }
            else -> {}
        }
        list.onAction(action, ui)
        return true
    }

    override fun isAnimating(nowMs: Long) = super.isAnimating(nowMs) || list.isAnimating(nowMs)
}

/** Yes/no question. */
class ConfirmOverlay(
    private val title: String,
    private val message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
) : Overlay() {
    private val list = MenuList(
        listOf(
            MenuItem.Action("Cancel", Icons.Close) { },
            MenuItem.Action(confirmLabel, Icons.Check) { onConfirm() },
        ),
    )

    override fun render(g: Canvas, viewport: IntRect, ui: Ui) {
        val theme = ui.theme
        val a = appearProgress(ui)
        val msgFont = theme.type.body
        val innerW = viewport.width - 2 * theme.spacing.xxl - 2 * theme.spacing.l
        val lines = TextLayout.wrap(msgFont, message, innerW)
        val titleH = theme.type.title.lineHeight + theme.spacing.s
        val msgH = lines.size * (msgFont.lineHeight + 4) + theme.spacing.m
        val listH = 2 * theme.spacing.rowHeight
        val inner = panel(g, viewport, titleH + msgH + listH, theme, a)
        g.drawTextIn(title, inner.takeTop(theme.type.title.lineHeight), theme.type.title, theme.levels.textStrong)
        var y = inner.top + titleH
        for (line in lines) {
            g.drawText(line, inner.left.toFloat(), (y + msgFont.ascent).toFloat(), msgFont, theme.levels.textDim)
            y += msgFont.lineHeight + 4
        }
        list.render(g, IntRect(inner.left, y + theme.spacing.m, inner.right, inner.bottom), ui)
    }

    override fun onAction(action: Action, ui: Ui): Boolean {
        when (action) {
            Action.Back -> dismiss()
            Action.Select -> {
                dismiss()
                list.onAction(action, ui)
            }
            else -> list.onAction(action, ui)
        }
        return true
    }
}

/** Short non-blocking message at the bottom of the screen. */
class ToastOverlay(private val text: String, private val icon: Int?) : Overlay() {
    override val modal: Boolean get() = false

    override fun render(g: Canvas, viewport: IntRect, ui: Ui) {
        val theme = ui.theme
        val a = appearProgress(ui)
        val font = theme.type.bodyStrong
        val iconSize = 24
        val maxW = viewport.width - 2 * theme.spacing.xxl
        val s = TextLayout.ellipsize(font, text, maxW - 60)
        val w = TextLayout.width(font, s) + (if (icon != null) iconSize + 10 else 0) + 2 * theme.spacing.xl
        val h = 48
        val x = viewport.left + (viewport.width - w) / 2
        val y = viewport.bottom - h - theme.spacing.l + ((1 - a) * 12).roundToInt()
        g.fillRoundRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h / 2f, theme.levels.background)
        g.strokeRoundRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h / 2f, theme.shapes.stroke, (theme.levels.outline * a).roundToInt())
        var tx = x + theme.spacing.xl
        if (icon != null) {
            g.drawIcon(icon, tx.toFloat(), (y + (h - iconSize) / 2).toFloat(), iconSize, theme.type.icons(iconSize), theme.levels.text)
            tx += iconSize + 10
        }
        g.drawTextIn(s, IntRect(tx, y, x + w, y + h), font, theme.levels.textStrong)
    }

    override fun onAction(action: Action, ui: Ui): Boolean = false
}

/** Popup for a newly posted phone notification. Select opens it, Back dismisses. */
class NotificationPopup(val notification: PhoneNotification, private val onOpen: (PhoneNotification) -> Unit) : Overlay() {
    override val modal: Boolean get() = false
    override val keepAwake: Boolean get() = true

    override fun render(g: Canvas, viewport: IntRect, ui: Ui) {
        val theme = ui.theme
        val a = appearProgress(ui)
        val lv = theme.levels
        val w = viewport.width - 2 * theme.spacing.xl
        val h = 124
        val x = viewport.left + theme.spacing.xl
        val y = viewport.top + theme.spacing.m - ((1 - a) * 16).roundToInt()
        val r = IntRect.of(x, y, w, h)
        g.fillRoundRect(r, theme.shapes.radiusLarge, lv.background)
        g.strokeRoundRect(r, theme.shapes.radiusLarge, theme.shapes.stroke, (lv.outline * a).roundToInt())
        val inner = r.inset(theme.spacing.l, theme.spacing.m)
        val cap = theme.type.caption
        val iconX = inner.left
        val bmp = notification.icon
        if (bmp != null && bmp.width <= 32) {
            g.drawImage(bmp, iconX, inner.top, transparentBlack = true)
        } else {
            g.drawIcon(Icons.Notifications, iconX.toFloat(), inner.top.toFloat() - 2, 20, theme.type.icons(20), lv.textDim)
        }
        g.drawTextIn(notification.appName, IntRect(iconX + 28, inner.top, inner.right, inner.top + 20), cap, lv.textDim)
        val titleFont = theme.type.bodyStrong
        g.drawTextIn(TextLayout.sanitize(notification.title), IntRect(inner.left, inner.top + 22, inner.right, inner.top + 22 + titleFont.lineHeight), titleFont, lv.textStrong)
        val body = TextLayout.sanitize(notification.text.replace('\n', ' '))
        g.drawTextIn(body, IntRect(inner.left, inner.top + 24 + titleFont.lineHeight, inner.right, inner.bottom), theme.type.body, lv.text, vAlign = com.madtreasures.faceclaw.core.gfx.VAlign.Top)
    }

    override fun onAction(action: Action, ui: Ui): Boolean = when (action) {
        Action.Select -> {
            dismiss()
            onOpen(notification)
            true
        }
        Action.Back -> {
            dismiss()
            true
        }
        else -> false
    }
}

/** Full attention alert (timer finished, alarm). */
class AlertOverlay(
    private val title: String,
    private val message: String?,
    private val icon: Int,
    actions: List<MenuItem.Action>,
) : Overlay() {
    private val list = MenuList(actions)

    override fun render(g: Canvas, viewport: IntRect, ui: Ui) {
        val theme = ui.theme
        val a = appearProgress(ui)
        val pulse = ((ui.nowMs / 600) % 2 == 0L)
        val listH = list.items.size * theme.spacing.rowHeight
        val inner = panel(g, viewport, 64 + 48 + listH + 40, theme, a)
        g.drawIcon(icon, (inner.centerX - 32).toFloat(), inner.top.toFloat(), 64, theme.type.icons(64), if (pulse) theme.levels.textStrong else theme.levels.textDim)
        g.drawTextIn(title, IntRect(inner.left, inner.top + 70, inner.right, inner.top + 110), theme.type.title, theme.levels.textStrong, HAlign.Center)
        if (message != null) g.drawTextIn(message, IntRect(inner.left, inner.top + 110, inner.right, inner.top + 140), theme.type.body, theme.levels.textDim, HAlign.Center)
        list.render(g, IntRect(inner.left, inner.bottom - listH, inner.right, inner.bottom), ui)
    }

    override fun onAction(action: Action, ui: Ui): Boolean {
        if (action == Action.Select) dismiss()
        list.onAction(action, ui)
        return true
    }

    override fun isAnimating(nowMs: Long): Boolean = true
}
