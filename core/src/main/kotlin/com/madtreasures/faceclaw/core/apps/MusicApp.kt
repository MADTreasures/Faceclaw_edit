package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.shell.GlanceCard
import com.madtreasures.faceclaw.core.shell.GlanceProvider
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.Drawing
import kotlin.math.roundToInt

/** Now-playing remote for whatever media app is active on the phone. */
class MusicApp : GlassApp() {
    override fun createRootScreen(): Screen = NowPlayingScreen()

    private enum class Control(val icon: Int, val label: String) {
        Previous(Icons.SkipPrevious, "Previous"),
        PlayPause(Icons.PlayArrow, "Play / pause"),
        Next(Icons.SkipNext, "Next"),
        VolumeDown(Icons.VolumeDown, "Volume down"),
        VolumeUp(Icons.VolumeUp, "Volume up"),
    }

    private inner class NowPlayingScreen : Screen() {
        private var focus = Control.PlayPause
        private val focusX = AnimatedFloat(-1f)
        override val title: String get() = "Music"
        override val refreshIntervalMs: Long? get() = if (ui.services.media.state.value?.playing == true) 1000L else null

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            val lv = th.levels
            val state = ui.services.media.state.value
            if (state == null || (state.title == null && state.artist == null)) {
                g.drawIcon(Icons.MusicNote, (bounds.centerX - 32).toFloat(), (bounds.top + 70).toFloat(), 64, th.type.icons(64), lv.textFaint)
                g.drawTextIn("Nothing playing", IntRect(bounds.left, bounds.top + 150, bounds.right, bounds.top + 190), th.type.title, lv.textDim, HAlign.Center)
                g.drawTextIn("Start music on your phone", IntRect(bounds.left, bounds.top + 192, bounds.right, bounds.top + 222), th.type.body, lv.textFaint, HAlign.Center)
                return
            }
            // artwork
            var textLeft = bounds.left
            val art = state.art
            val artSize = 150
            if (art != null) {
                val scaled = if (art.width != artSize || art.height != artSize) art.scaled(artSize, artSize) else art
                g.drawImage(scaled, bounds.left, bounds.top + 8)
                g.strokeRoundRect(bounds.left.toFloat(), bounds.top + 8f, artSize.toFloat(), artSize.toFloat(), 6f, 1f, lv.divider)
                textLeft += artSize + th.spacing.xl
            } else {
                g.strokeRoundRect(bounds.left.toFloat(), bounds.top + 8f, artSize.toFloat(), artSize.toFloat(), th.shapes.radiusMedium, th.shapes.stroke, lv.divider)
                g.drawIcon(Icons.Album, (bounds.left + artSize / 2 - 32).toFloat(), (bounds.top + 8 + artSize / 2 - 32).toFloat(), 64, th.type.icons(64), lv.textFaint)
                textLeft += artSize + th.spacing.xl
            }
            val textRect = IntRect(textLeft, bounds.top + 8, bounds.right, bounds.top + 8 + artSize)
            g.drawTextIn(state.title ?: "Unknown title", textRect.takeTop(40), th.type.title, lv.textStrong)
            g.drawTextIn(state.artist ?: "", textRect.dropTop(44).takeTop(30), th.type.body, lv.text)
            g.drawTextIn(state.album ?: "", textRect.dropTop(76).takeTop(26), th.type.caption, lv.textFaint)
            state.sourceApp?.let { g.drawTextIn(it, textRect.takeBottom(24), th.type.caption, lv.textFaint) }

            // progress
            val now = ui.nowMs
            val pos = state.positionAt(now)
            val py = bounds.top + 8 + artSize + 26
            if (state.durationMs > 0) {
                Drawing.progressBar(g, IntRect(bounds.left, py, bounds.right, py + 6), pos.toFloat() / state.durationMs, th)
                val cap = th.type.caption
                g.drawTextIn(Drawing.formatDuration(pos), IntRect(bounds.left, py + 10, bounds.centerX, py + 34), cap, lv.textDim)
                g.drawTextIn("-" + Drawing.formatDuration(state.durationMs - pos), IntRect(bounds.centerX, py + 10, bounds.right, py + 34), cap, lv.textDim, HAlign.End)
            }

            // controls
            val controls = Control.values()
            val cy = bounds.bottom - 34
            val spacing = bounds.width / controls.size
            val focusIndex = controls.indexOf(focus)
            val targetX = bounds.left + spacing * focusIndex + spacing / 2f
            if (focusX.target != targetX) {
                if (focusX.target < 0) focusX.snapTo(targetX) else focusX.animateTo(targetX, now, th.motion.normalMs)
            }
            val fx = focusX.value(now)
            g.fillRoundRect(fx - 34, cy - 28f, 68f, 56f, 28f, lv.surface)
            g.strokeRoundRect(fx - 34, cy - 28f, 68f, 56f, 28f, th.shapes.stroke, lv.outline)
            for ((i, c) in controls.withIndex()) {
                val cx = bounds.left + spacing * i + spacing / 2
                val icon = if (c == Control.PlayPause && state.playing) Icons.Pause else c.icon
                val size = if (c == Control.PlayPause) 40 else 32
                g.drawIcon(icon, (cx - size / 2).toFloat(), (cy - size / 2).toFloat(), size, th.type.icons(size), if (c == focus) lv.textStrong else lv.textDim)
            }
        }

        override fun onAction(action: Action): Boolean {
            val controls = Control.values()
            val media = ui.services.media
            when (action) {
                Action.Previous -> focus = controls[(focus.ordinal - 1).coerceAtLeast(0)]
                Action.Next -> focus = controls[(focus.ordinal + 1).coerceAtMost(controls.size - 1)]
                Action.Select -> when (focus) {
                    Control.Previous -> media.previous()
                    Control.PlayPause -> media.playPause()
                    Control.Next -> media.next()
                    Control.VolumeDown -> media.volumeDown()
                    Control.VolumeUp -> media.volumeUp()
                }
                else -> return false
            }
            ui.invalidate()
            return true
        }

        override fun isAnimating(nowMs: Long) = focusX.isRunning(nowMs)
    }

    companion object {
        val glance = GlanceProvider { services, now ->
            val s = services.media.state.value ?: return@GlanceProvider null
            if (s.title == null) return@GlanceProvider null
            GlanceCard(
                key = "music",
                icon = if (s.playing) Icons.MusicNote else Icons.Pause,
                title = listOfNotNull(s.title, s.artist).joinToString("  ·  "),
                progress = if (s.durationMs > 0) (s.positionAt(now).toFloat() / s.durationMs).coerceIn(0f, 1f) else null,
                detail = if (s.durationMs > 0) Drawing.formatDuration(s.positionAt(now)) else null,
                appId = "music",
                priority = if (s.playing) 50 else 5,
            ).takeIf { s.playing || (s.positionMs > 0) }?.let { it.copy(progress = it.progress?.let { p -> (p * 1000).roundToInt() / 1000f }) }
        }
    }
}
