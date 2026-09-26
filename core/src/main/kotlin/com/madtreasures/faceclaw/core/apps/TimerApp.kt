package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.services.TimerService
import com.madtreasures.faceclaw.core.shell.GlanceCard
import com.madtreasures.faceclaw.core.shell.GlanceProvider
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.Drawing
import com.madtreasures.faceclaw.core.ui.widgets.MenuList
import kotlinx.coroutines.launch

/** Timers and a stopwatch. The state lives in [TimerService] so it survives closing the app. */
class TimerApp(private val timers: TimerService) : GlassApp() {
    override fun createRootScreen(): Screen = RootScreen()

    private inner class RootScreen : Screen() {
        private val list = MenuList()
        override val title: String get() = "Timer"
        override val refreshIntervalMs: Long get() = 1000

        override fun onAttach() {
            ui.scope.launch { timers.timers.collect { rebuild() } }
            ui.scope.launch { timers.stopwatch.collect { rebuild() } }
        }

        private fun rebuild() {
            val now = ui.nowMs
            val items = ArrayList<MenuItem>()
            val running = timers.timers.value
            if (running.isNotEmpty()) {
                items += MenuItem.Header("Running")
                for (t in running) {
                    items += MenuItem.Custom(t.label, 64, key = "timer-${t.id}", draw = { g, row, selected, th ->
                        val lv = th.levels
                        val n = ui.nowMs
                        g.drawIcon(if (t.running) Icons.Timer else Icons.Pause, (row.left + 18).toFloat(), (row.centerY - 14).toFloat(), 28, th.type.icons(28), if (selected) lv.textStrong else lv.textDim)
                        val left = row.left + 60
                        g.drawTextIn(Drawing.formatDuration(t.remaining(n)), IntRect(left, row.top + 6, row.right - 18, row.top + 38), th.type.title, if (selected) lv.textStrong else lv.text)
                        g.drawTextIn(t.label, IntRect(left, row.top + 6, row.right - 18, row.top + 38), th.type.caption, lv.textFaint, HAlign.End)
                        Drawing.progressBar(g, IntRect(left, row.bottom - 16, row.right - 18, row.bottom - 12), t.progress(n), th, if (selected) lv.text else lv.textDim)
                    }) { ui.push(TimerDetailScreen(t.id)) }
                }
            }
            val sw = timers.stopwatch.value
            items += MenuItem.Header("Stopwatch")
            items += MenuItem.Action("Stopwatch", Icons.AvTimer, detail = if (sw.running || sw.accumulatedMs > 0) Drawing.formatDuration(sw.elapsed(now)) else null, key = "stopwatch") {
                ui.push(StopwatchScreen())
            }
            items += MenuItem.Header("New timer")
            for (min in listOf(1, 3, 5, 10, 15, 25, 30, 45, 60)) {
                items += MenuItem.Action("$min min", Icons.HourglassEmpty, key = "preset-$min") {
                    timers.start(min * 60_000L)
                    ui.toast("Timer $min min started", Icons.Timer)
                }
            }
            items += MenuItem.Link("Custom…", Icons.Tune, key = "custom") { CustomTimerScreen() }
            list.setItems(items)
            ui.invalidate()
        }

        override fun render(g: Canvas, bounds: IntRect) {
            list.render(g, bounds.dropRight(8), ui)
        }

        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    private inner class TimerDetailScreen(private val id: Int) : Screen() {
        override val title: String get() = "Timer"
        override val refreshIntervalMs: Long get() = 250
        override val keepAwake: Boolean get() = true

        override fun render(g: Canvas, bounds: IntRect) {
            val t = timers.timers.value.firstOrNull { it.id == id }
            if (t == null) {
                ui.pop()
                return
            }
            val th = theme
            val now = ui.nowMs
            val cx = bounds.centerX.toFloat()
            val cy = (bounds.top + 150).toFloat()
            g.drawArc(cx, cy, 130f, 6f, 0f, 360f, th.levels.surfaceStrong)
            g.drawArc(cx, cy, 130f, 6f, 0f, -360f * (1f - t.progress(now)), th.levels.textStrong)
            g.drawTextIn(Drawing.formatDuration(t.remaining(now)), IntRect(bounds.left, (cy - 45).toInt(), bounds.right, (cy + 35).toInt()), th.type.displaySmall, th.levels.textStrong, HAlign.Center)
            g.drawTextIn(if (t.running) t.label else "Paused", IntRect(bounds.left, (cy + 40).toInt(), bounds.right, (cy + 70).toInt()), th.type.body, th.levels.textDim, HAlign.Center)
            g.drawTextIn("Tap: ${if (t.running) "pause" else "resume"}   ·   Hold: more", bounds.takeBottom(28), th.type.caption, th.levels.textFaint, HAlign.Center)
        }

        override fun onAction(action: Action): Boolean {
            val t = timers.timers.value.firstOrNull { it.id == id } ?: return false
            return when (action) {
                Action.Select -> {
                    if (t.running) timers.pause(id) else timers.resume(id)
                    true
                }
                else -> false
            }
        }

        override fun menuItems(): List<MenuItem> = listOf(
            MenuItem.Action("+1 minute", Icons.Add) { timers.addTime(id, 60_000) },
            MenuItem.Action("+5 minutes", Icons.Add) { timers.addTime(id, 300_000) },
            MenuItem.Action("Cancel timer", Icons.Delete) {
                timers.cancel(id)
                ui.pop()
            },
        )
    }

    private inner class CustomTimerScreen : Screen() {
        private var minutes = 20
        private var seconds = 0
        private val list = MenuList(
            listOf(
                MenuItem.Stepper("Minutes", Icons.HourglassEmpty, 0..180, 1, get = { minutes }, set = { minutes = it }),
                MenuItem.Stepper("Seconds", Icons.HourglassBottom, 0..55, 5, get = { seconds }, set = { seconds = it }),
                MenuItem.Action("Start", Icons.PlayArrow) {
                    val ms = minutes * 60_000L + seconds * 1000L
                    if (ms > 0) {
                        timers.start(ms)
                        ui.toast("Timer started", Icons.Timer)
                        ui.pop()
                    }
                },
            ),
        )
        override val title: String get() = "Custom timer"
        override fun render(g: Canvas, bounds: IntRect) = list.render(g, bounds, ui)
        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    private inner class StopwatchScreen : Screen() {
        override val title: String get() = "Stopwatch"
        override val keepAwake: Boolean get() = timers.stopwatch.value.running
        override val refreshIntervalMs: Long get() = if (timers.stopwatch.value.running) 100 else 1000

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            val sw = timers.stopwatch.value
            val now = ui.nowMs
            val ms = sw.elapsed(now)
            val main = Drawing.formatDuration(ms)
            val tenths = ((ms / 100) % 10).toString()
            val font = th.type.displayMedium
            val w = com.madtreasures.faceclaw.core.gfx.TextLayout.width(font, main)
            val x = bounds.centerX - (w + 40) / 2f
            val baseline = bounds.top + 40f + font.capHeight
            g.drawText(main, x, baseline, font, th.levels.textStrong)
            g.drawText(".$tenths", x + w + 4, baseline, th.type.displaySmall, th.levels.textDim)
            var y = baseline + 40
            for ((i, lap) in sw.laps.take(4).withIndex()) {
                g.drawTextIn("Lap ${sw.laps.size - i}", IntRect(bounds.left + 60, y.toInt(), bounds.centerX, y.toInt() + 30), th.type.body, th.levels.textDim)
                g.drawTextIn(Drawing.formatDuration(lap), IntRect(bounds.centerX, y.toInt(), bounds.right - 60, y.toInt() + 30), th.type.body, th.levels.text, HAlign.End)
                y += 32
            }
            g.drawTextIn(if (sw.running) "Tap: stop   ·   Hold: lap / reset" else "Tap: start   ·   Hold: reset", bounds.takeBottom(28), th.type.caption, th.levels.textFaint, HAlign.Center)
        }

        override fun onAction(action: Action): Boolean = when (action) {
            Action.Select -> {
                timers.stopwatchToggle()
                true
            }
            else -> false
        }

        override fun menuItems(): List<MenuItem> = listOf(
            MenuItem.Action("Lap", Icons.Flag) { timers.stopwatchLap() },
            MenuItem.Action("Reset", Icons.Replay) { timers.stopwatchReset() },
        )
    }

    companion object {
        fun glance(timers: TimerService) = GlanceProvider { _, now ->
            val t = timers.timers.value.minByOrNull { it.remaining(now) }
            if (t != null) {
                return@GlanceProvider GlanceCard(
                    key = "timer",
                    icon = if (t.running) Icons.Timer else Icons.Pause,
                    title = "Timer  ·  ${t.label}",
                    detail = Drawing.formatDuration(t.remaining(now)),
                    progress = t.progress(now),
                    appId = "timer",
                    priority = 60,
                )
            }
            val sw = timers.stopwatch.value
            if (sw.running) GlanceCard("stopwatch", Icons.AvTimer, "Stopwatch", Drawing.formatDuration(sw.elapsed(now)), appId = "timer", priority = 55) else null
        }
    }
}
