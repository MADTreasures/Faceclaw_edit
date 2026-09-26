package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.platform.LinkState
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.ui.Ui
import com.madtreasures.faceclaw.core.ui.widgets.Drawing
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The thin line at the top: time and screen title on the left, indicators on the right. */
object StatusBar {
    private val fmt24 = DateTimeFormatter.ofPattern("HH:mm")
    private val fmt12 = DateTimeFormatter.ofPattern("h:mm")

    fun timeText(ui: Ui): String {
        val services = ui.services
        val t = Instant.ofEpochMilli(ui.nowMs).atZone(services.clock.zone())
        return (if (services.settings[Prefs.clock24h]) fmt24 else fmt12).format(t)
    }

    fun render(g: Canvas, r: IntRect, ui: Ui, title: String?) {
        val theme = ui.theme
        val lv = theme.levels
        val font = theme.type.status
        val baseline = (r.top + (r.height + font.capHeight) / 2).toFloat()
        var x = r.left.toFloat()
        x += g.drawText(timeText(ui), x, baseline, font, lv.text)
        if (!title.isNullOrEmpty()) {
            x += 10f
            g.fillCircle(x + 2, baseline - font.capHeight / 2f, 2f, lv.textFaint)
            x += 12f
            val maxW = (r.right - 160 - x).toInt().coerceAtLeast(0)
            g.drawText(TextLayout.ellipsize(font, title, maxW), x, baseline, font, lv.textDim)
        }

        renderIndicators(g, r, ui)
    }

    /** Draws battery, link, media and notification indicators right-aligned in [r]; returns the left edge used. */
    fun renderIndicators(g: Canvas, r: IntRect, ui: Ui): Float {
        val theme = ui.theme
        val lv = theme.levels
        val font = theme.type.status
        val baseline = (r.top + (r.height + font.capHeight) / 2).toFloat()
        val status = ui.services.status.value
        var right = r.right.toFloat()
        val icons = theme.type.icons(20)
        val iconTop = r.top + (r.height - 20) / 2f
        val batt = status.glassesBattery
        if (batt != null) {
            Drawing.battery(g, right - 27f, r.top + (r.height - 12) / 2f, batt, status.glassesCharging, lv.textDim)
            right -= 33f
            val s = "$batt%"
            val w = TextLayout.width(font, s)
            g.drawText(s, right - w, baseline, font, lv.textDim)
            right -= w + 12
        }
        when (status.link) {
            LinkState.Disconnected, LinkState.Scanning, LinkState.Connecting -> {
                g.drawIcon(Icons.BluetoothDisabled, right - 20, iconTop, 20, icons, lv.textDim)
                right -= 28
            }
            else -> {}
        }
        val media = ui.services.media.state.value
        if (media?.playing == true) {
            g.drawIcon(Icons.MusicNote, right - 20, iconTop, 20, icons, lv.textDim)
            right -= 28
        }
        val count = ui.services.notifications.active.value.size
        if (count > 0) {
            val s = count.toString()
            val w = TextLayout.width(font, s)
            g.drawText(s, right - w, baseline, font, lv.textDim)
            right -= w + 4
            g.drawIcon(Icons.Notifications, right - 20, iconTop, 20, icons, lv.textDim)
            right -= 28
        }
        return right
    }
}
