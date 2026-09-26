package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.FontLibrary
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.TextPager

/**
 * Reads a script (entered in the phone app) with large type. Tap starts/stops the
 * automatic scroll; scrolling moves manually; the long-press menu changes speed.
 */
class TeleprompterApp : GlassApp() {
    override fun createRootScreen(): Screen = PrompterScreen()

    private inner class PrompterScreen : Screen() {
        private val pager = TextPager("", font = { FontLibrary.get("inter-medium-28") }, lineSpacing = 10, level = { it.levels.textStrong })
        private var playing = false
        private var lastTick = 0L
        private var position = 0f

        override val title: String get() = "Teleprompter"
        override val fullscreen: Boolean get() = playing
        override val keepAwake: Boolean get() = playing
        override val refreshIntervalMs: Long? get() = if (playing) 50 else null

        override fun onShow() {
            val text = ui.services.settings[Prefs.teleprompterText].ifBlank { SAMPLE }
            if (text != pager.text) {
                pager.setText(text)
                position = 0f
            }
        }

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            if (playing) {
                val now = ui.nowMs
                val speed = ui.services.settings[Prefs.teleprompterSpeed]
                if (lastTick > 0) position += speed * (now - lastTick) / 1000f
                lastTick = now
                pager.scrollTo(position, ui)
                if (pager.atEnd) playing = false
            }
            val area = if (playing) bounds.inset(th.spacing.xxl, th.spacing.l) else bounds
            pager.render(g, area.dropBottom(if (playing) 0 else 30), ui)
            if (!playing) {
                val hint = if (pager.atEnd) "End  ·  Hold for options" else "Tap to start  ·  Hold for options"
                g.drawTextIn(hint, area.takeBottom(26), th.type.caption, th.levels.textFaint, HAlign.Center)
            } else {
                // reading line marker
                g.fillRoundRect((area.left - 16).toFloat(), (area.top + 12).toFloat(), 4f, 30f, 2f, th.levels.textDim)
            }
        }

        override fun onAction(action: Action): Boolean = when (action) {
            Action.Select -> {
                playing = !playing
                lastTick = 0
                position = pager.scrollTarget
                true
            }
            Action.Previous, Action.Next -> {
                playing = false
                pager.onAction(action, ui)
                position = pager.scrollTarget
                true
            }
            else -> false
        }

        override fun menuItems(): List<MenuItem> = listOf(
            MenuItem.Stepper("Speed", Icons.Speed, 5..200, 5, format = { "$it px/s" },
                get = { ui.services.settings[Prefs.teleprompterSpeed] }, set = { ui.services.settings[Prefs.teleprompterSpeed] = it }),
            MenuItem.Action("Restart", Icons.Replay) {
                playing = false
                position = 0f
                pager.scrollTo(0f, ui)
            },
        )

        override fun isAnimating(nowMs: Long) = pager.isAnimating(nowMs)
    }

    companion object {
        const val SAMPLE = "Welcome to the teleprompter.\n\nEnter your own script in the phone app under Teleprompter. " +
            "Tap to start scrolling, tap again to pause. Scroll with the ring or the touchpad to move manually. " +
            "Hold for speed and restart."
    }
}
