package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.shell.GlanceCard
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.shell.Shell
import com.madtreasures.faceclaw.core.shell.StatusBar
import com.madtreasures.faceclaw.core.shell.AppRegistry
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.Drawing
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The home screen: a large clock with the date and a column of live glance cards
 * (now playing, timers, next event, weather...). Nothing focused = the clock; Select on the
 * clock opens the launcher, Select on a card opens its app.
 */
class HomeApp(private val registry: AppRegistry) : GlassApp() {
    override fun createRootScreen(): Screen = HomeScreen(registry)
}

class HomeScreen(private val registry: AppRegistry) : Screen() {
    private var focus = -1
    private var cards: List<GlanceCard> = emptyList()
    private val highlight = AnimatedFloat(0f)
    private val highlightAlpha = AnimatedFloat(0f)
    private val listScroll = AnimatedFloat(0f)

    override val fullscreen: Boolean get() = true
    override val refreshIntervalMs: Long? get() = if (ui.services.settings[Prefs.clockSeconds] || cards.any { it.progress != null }) 1000L else null

    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    private val time24 = DateTimeFormatter.ofPattern("HH:mm")
    private val time12 = DateTimeFormatter.ofPattern("h:mm")
    private val secFmt = DateTimeFormatter.ofPattern("ss")
    private val ampmFmt = DateTimeFormatter.ofPattern("a", Locale.ENGLISH)

    override fun onShow() {
        focus = -1
        highlightAlpha.snapTo(0f)
    }

    override fun render(g: Canvas, bounds: IntRect) {
        val th = theme
        val lv = th.levels
        val sp = th.spacing
        val now = ui.nowMs
        cards = registry.glanceCards(ui.services, now)
        if (focus >= cards.size) focus = cards.size - 1

        val area = bounds.inset(sp.xxl, sp.l)
        val zoned = Instant.ofEpochMilli(now).atZone(ui.services.clock.zone())
        val is24 = ui.services.settings[Prefs.clock24h]

        // top line: date left, indicators right
        val top = area.takeTop(sp.statusBarHeight)
        g.drawTextIn(dateFmt.format(zoned), top, th.type.status, lv.textDim)
        StatusBar.renderIndicators(g, top, ui)

        // clock
        val clockFont = if (cards.size >= 3) th.type.displayMedium else th.type.display
        val timeText = (if (is24) time24 else time12).format(zoned)
        val clockTop = top.bottom + sp.m
        val baseline = clockTop + clockFont.capHeight
        var x = area.left.toFloat()
        x += g.drawText(timeText, x, baseline.toFloat(), clockFont, if (focus < 0) lv.textStrong else lv.text)
        val small = th.type.displaySmall
        if (ui.services.settings[Prefs.clockSeconds]) {
            g.drawText(secFmt.format(zoned), x + 12, baseline.toFloat(), small, lv.textFaint)
        } else if (!is24) {
            g.drawText(ampmFmt.format(zoned), x + 12, baseline.toFloat(), th.type.title, lv.textFaint)
        }

        // cards
        val listTop = baseline + sp.xl
        val list = IntRect(area.left - sp.m, listTop, area.right + sp.m, area.bottom)
        val rowH = 60
        if (cards.isEmpty()) {
            g.drawTextIn("Tap for apps", IntRect(list.left + sp.m, list.bottom - 30, list.right, list.bottom), th.type.caption, lv.textFaint)
            return
        }
        val visibleRows = (list.height / rowH).coerceAtLeast(1)
        val targetScroll = if (focus < visibleRows) 0f else ((focus - visibleRows + 1) * rowH).toFloat()
        if (listScroll.target != targetScroll) listScroll.animateTo(targetScroll, now, th.motion.normalMs)
        val scroll = listScroll.value(now)
        g.withSave {
            clipRect(list)
            val a = highlightAlpha.value(now)
            if (a > 0.01f) {
                val hy = list.top + highlight.value(now) - scroll
                fillRoundRect(list.left.toFloat(), hy + 3, list.width.toFloat(), (rowH - 6).toFloat(), 18f, (lv.surface * a).roundToInt())
                strokeRoundRect(list.left.toFloat(), hy + 3, list.width.toFloat(), (rowH - 6).toFloat(), 18f, th.shapes.stroke, (lv.outline * a).roundToInt())
            }
            for ((i, card) in cards.withIndex()) {
                val y = (list.top + i * rowH - scroll).roundToInt()
                if (y > list.bottom) break
                drawCard(this, card, IntRect(list.left + sp.m, y, list.right - sp.m, y + rowH), i == focus)
            }
        }
        if (focus < 0) {
            val hint = "Tap for apps  ·  scroll for more"
            if (cards.size * rowH <= list.height - 30) {
                g.drawTextIn(hint, IntRect(list.left + sp.m, list.bottom - 26, list.right, list.bottom), th.type.caption, lv.textFaint)
            }
        }
    }

    private fun drawCard(g: Canvas, card: GlanceCard, r: IntRect, focused: Boolean) {
        val th = theme
        val lv = th.levels
        val iconSize = 28
        g.drawIcon(card.icon, (r.left + 6).toFloat(), (r.centerY - iconSize / 2).toFloat(), iconSize, th.type.icons(iconSize), if (focused) lv.textStrong else lv.text)
        val textLeft = r.left + 6 + iconSize + 16
        val right = r.right - 6
        val titleFont = if (focused) th.type.bodyStrong else th.type.body
        val detail = card.detail
        var textRight = right
        if (detail != null) {
            val df = th.type.caption
            val s = TextLayout.ellipsize(df, detail, r.width / 3)
            val w = TextLayout.width(df, s)
            g.drawTextIn(s, IntRect(right - w, r.top, right, r.bottom), df, if (focused) lv.text else lv.textDim, HAlign.End)
            textRight = right - w - 14
        }
        val progress = card.progress
        if (progress != null) {
            g.drawTextIn(card.title, IntRect(textLeft, r.top, textRight, r.bottom - 14), titleFont, if (focused) lv.textStrong else lv.text)
            Drawing.progressBar(g, IntRect(textLeft, r.bottom - 17, textRight, r.bottom - 13), progress, th, if (focused) lv.textStrong else lv.textDim)
        } else {
            g.drawTextIn(card.title, IntRect(textLeft, r.top, textRight, r.bottom), titleFont, if (focused) lv.textStrong else lv.text)
        }
    }

    override fun onAction(action: Action): Boolean {
        val now = ui.nowMs
        val dur = if (theme.motion.enabled) theme.motion.normalMs else 0L
        when (action) {
            Action.Next -> {
                if (focus < cards.size - 1) {
                    focus++
                    highlight.animateTo(focus * 60f, now, if (focus == 0) 0 else dur)
                    highlightAlpha.animateTo(1f, now, dur)
                }
            }
            Action.Previous -> {
                if (focus >= 0) {
                    focus--
                    if (focus >= 0) highlight.animateTo(focus * 60f, now, dur) else highlightAlpha.animateTo(0f, now, dur)
                }
            }
            Action.Select -> {
                val card = cards.getOrNull(focus)
                if (card?.appId != null) ui.openApp(card.appId) else ui.openApp(Shell.LAUNCHER)
            }
            else -> return false
        }
        ui.invalidate()
        return true
    }

    override fun isAnimating(nowMs: Long): Boolean =
        highlight.isRunning(nowMs) || highlightAlpha.isRunning(nowMs) || listScroll.isRunning(nowMs)
}
