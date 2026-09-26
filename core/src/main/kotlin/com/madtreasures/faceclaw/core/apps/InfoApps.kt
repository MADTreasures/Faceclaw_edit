package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.platform.CalendarEvent
import com.madtreasures.faceclaw.core.platform.Heading
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.WeatherCondition
import com.madtreasures.faceclaw.core.shell.GlanceCard
import com.madtreasures.faceclaw.core.shell.GlanceProvider
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.AnimatedFloat
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.MenuList
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A heads-up compass tape: the heading in large digits over a scrolling scale. */
class CompassApp : GlassApp() {
    override fun createRootScreen(): Screen = CompassScreen()

    private class CompassScreen : Screen() {
        private var heading: Heading? = null
        private val shown = AnimatedFloat(0f)
        override val title: String get() = "Compass"
        override val keepAwake: Boolean get() = true

        override fun onAttach() {
            ui.scope.launch {
                ui.services.sensors.heading.collect { h ->
                    val prev = heading
                    heading = h
                    val cur = shown.value(ui.nowMs)
                    // animate along the shortest way round
                    var target = h.degrees
                    while (target - cur > 180) target -= 360
                    while (target - cur < -180) target += 360
                    if (prev == null) shown.snapTo(target) else shown.animateTo(target, ui.nowMs, 250)
                    ui.invalidate()
                }
            }
        }

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            val lv = th.levels
            val h = heading
            if (h == null) {
                g.drawIcon(Icons.Explore, (bounds.centerX - 32).toFloat(), (bounds.top + 60).toFloat(), 64, th.type.icons(64), lv.textFaint)
                g.drawTextIn("Waiting for heading…", IntRect(bounds.left, bounds.top + 140, bounds.right, bounds.top + 180), th.type.title, lv.textDim, HAlign.Center)
                g.drawTextIn("Needs the custom firmware's compass or the phone's sensor", IntRect(bounds.left, bounds.top + 184, bounds.right, bounds.top + 214), th.type.caption, lv.textFaint, HAlign.Center)
                return
            }
            val deg = ((shown.value(ui.nowMs) % 360) + 360) % 360
            val tapeY = bounds.top + 190
            val pxPerDeg = 6f
            val cx = bounds.centerX
            g.withSave {
                clipRect(IntRect(bounds.left, tapeY - 60, bounds.right, tapeY + 60))
                val span = (bounds.width / 2 / pxPerDeg).toInt() + 10
                for (d in (deg.toInt() - span)..(deg.toInt() + span)) {
                    if (d % 5 != 0) continue
                    val x = cx + (d - deg) * pxPerDeg
                    val norm = ((d % 360) + 360) % 360
                    val major = norm % 45 == 0
                    val tick = if (norm % 15 == 0) 22f else 10f
                    drawLine(x, tapeY - tick, x, tapeY.toFloat(), if (major) 3f else 1.5f, if (major) lv.text else lv.textFaint)
                    if (major) {
                        val label = when (norm) { 0 -> "N"; 45 -> "NE"; 90 -> "E"; 135 -> "SE"; 180 -> "S"; 225 -> "SW"; 270 -> "W"; 315 -> "NW"; else -> "" }
                        val f = if (norm % 90 == 0) th.type.title else th.type.bodyStrong
                        val w = TextLayout.width(f, label)
                        drawText(label, x - w / 2f, tapeY + 36f, f, if (norm == 0) lv.textStrong else lv.text)
                    } else if (norm % 15 == 0) {
                        val s = norm.toString()
                        val w = TextLayout.width(th.type.caption, s)
                        drawText(s, x - w / 2f, tapeY + 30f, th.type.caption, lv.textFaint)
                    }
                }
            }
            // centre marker
            g.fillPolygon(floatArrayOf(cx - 10f, cx + 10f, cx.toFloat()), floatArrayOf(tapeY - 44f, tapeY - 44f, tapeY - 28f), lv.textStrong)
            val text = "${deg.roundToInt() % 360}°"
            g.drawTextIn(text, IntRect(bounds.left, bounds.top + 10, bounds.right, bounds.top + 110), th.type.displaySmall, lv.textStrong, HAlign.Center)
            g.drawTextIn(cardinal(deg), IntRect(bounds.left, bounds.top + 104, bounds.right, bounds.top + 132), th.type.body, lv.textDim, HAlign.Center)
            if (h.accuracy in 0..1) g.drawTextIn("Low accuracy — move your head in a figure eight", bounds.takeBottom(26), th.type.caption, lv.textFaint, HAlign.Center)
        }

        private fun cardinal(d: Float): String {
            val names = listOf("North", "North-east", "East", "South-east", "South", "South-west", "West", "North-west")
            return names[(((d + 22.5f) % 360) / 45).toInt().coerceIn(0, 7)]
        }

        override fun isAnimating(nowMs: Long) = shown.isRunning(nowMs)
    }
}

/** Current conditions from the phone (Open-Meteo). */
class WeatherApp : GlassApp() {
    override fun createRootScreen(): Screen = WeatherScreen()

    private class WeatherScreen : Screen() {
        override val title: String get() = "Weather"

        override fun onAttach() {
            ui.scope.launch { ui.services.weather.current.collect { ui.invalidate() } }
            ui.services.weather.refresh()
        }

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            val lv = th.levels
            val w = ui.services.weather.current.value
            if (w == null) {
                g.drawIcon(Icons.Cloud, (bounds.centerX - 32).toFloat(), (bounds.top + 60).toFloat(), 64, th.type.icons(64), lv.textFaint)
                g.drawTextIn("No weather yet", IntRect(bounds.left, bounds.top + 140, bounds.right, bounds.top + 180), th.type.title, lv.textDim, HAlign.Center)
                g.drawTextIn("Allow location access in the phone app", IntRect(bounds.left, bounds.top + 184, bounds.right, bounds.top + 214), th.type.caption, lv.textFaint, HAlign.Center)
                return
            }
            val celsius = ui.services.settings[Prefs.weatherUnitsCelsius]
            fun t(c: Double) = if (celsius) "${c.roundToInt()}°" else "${(c * 9 / 5 + 32).roundToInt()}°"
            g.drawIcon(icon(w.condition), bounds.left.toFloat(), (bounds.top + 20).toFloat(), 64, th.type.icons(64), lv.text)
            g.drawText(t(w.temperatureC), (bounds.left + 90).toFloat(), (bounds.top + 20 + th.type.displayMedium.capHeight).toFloat(), th.type.displayMedium, lv.textStrong)
            g.drawTextIn(w.description, IntRect(bounds.left, bounds.top + 120, bounds.right, bounds.top + 156), th.type.title, lv.text)
            val hl = listOfNotNull(w.highC?.let { "High ${t(it)}" }, w.lowC?.let { "Low ${t(it)}" }).joinToString("   ")
            g.drawTextIn(hl, IntRect(bounds.left, bounds.top + 160, bounds.right, bounds.top + 190), th.type.body, lv.textDim)
            w.place?.let { g.drawTextIn(it, IntRect(bounds.left, bounds.top + 194, bounds.right, bounds.top + 222), th.type.caption, lv.textFaint) }
        }
    }

    companion object {
        fun icon(c: WeatherCondition): Int = when (c) {
            WeatherCondition.Clear -> Icons.WbSunny
            WeatherCondition.PartlyCloudy -> Icons.WbCloudy
            WeatherCondition.Cloudy -> Icons.Cloud
            WeatherCondition.Fog -> Icons.Air
            WeatherCondition.Drizzle, WeatherCondition.Rain -> Icons.Umbrella
            WeatherCondition.Snow -> Icons.AcUnit
            WeatherCondition.Thunderstorm -> Icons.Thunderstorm
            WeatherCondition.Unknown -> Icons.Cloud
        }

        val glance = GlanceProvider { services, _ ->
            val w = services.weather.current.value ?: return@GlanceProvider null
            val celsius = services.settings[Prefs.weatherUnitsCelsius]
            val temp = if (celsius) w.temperatureC.roundToInt() else (w.temperatureC * 9 / 5 + 32).roundToInt()
            GlanceCard("weather", icon(w.condition), "$temp°  ${w.description}", w.place, appId = "weather", priority = 10)
        }
    }
}

/** Today's and tomorrow's calendar events. */
class AgendaApp : GlassApp() {
    override fun createRootScreen(): Screen = AgendaScreen()

    private class AgendaScreen : Screen() {
        private val list = MenuList()
        override val title: String get() = "Agenda"

        override fun onAttach() {
            ui.scope.launch { ui.services.calendar.upcoming.collect { rebuild(it) } }
        }

        private fun rebuild(events: List<CalendarEvent>) {
            val zone = ui.services.clock.zone()
            val items = ArrayList<MenuItem>()
            var lastDay: String? = null
            for (e in events) {
                val day = dayLabel(e.startMs, ui.nowMs, zone)
                if (day != lastDay) {
                    items += MenuItem.Header(day)
                    lastDay = day
                }
                items += MenuItem.Action(e.title, Icons.Event, detail = if (e.allDay) "all day" else timeRange(e, zone), subtitle = e.location, key = e.id) {}
            }
            list.setItems(items)
            ui.invalidate()
        }

        override fun render(g: Canvas, bounds: IntRect) {
            if (list.items.isEmpty()) {
                val th = theme
                g.drawIcon(Icons.EventNote, (bounds.centerX - 32).toFloat(), (bounds.top + 60).toFloat(), 64, th.type.icons(64), th.levels.textFaint)
                g.drawTextIn("Nothing planned", IntRect(bounds.left, bounds.top + 140, bounds.right, bounds.top + 180), th.type.title, th.levels.textDim, HAlign.Center)
                return
            }
            list.render(g, bounds.dropRight(8), ui)
        }

        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    companion object {
        private val hm = DateTimeFormatter.ofPattern("HH:mm")

        fun timeRange(e: CalendarEvent, zone: ZoneId): String =
            hm.format(Instant.ofEpochMilli(e.startMs).atZone(zone)) + "–" + hm.format(Instant.ofEpochMilli(e.endMs).atZone(zone))

        fun dayLabel(ms: Long, now: Long, zone: ZoneId): String {
            val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return when (d) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH).format(d)
            }
        }

        val glance = GlanceProvider { services, now ->
            val next = services.calendar.upcoming.value.firstOrNull { it.endMs > now && !it.allDay } ?: return@GlanceProvider null
            val inMin = (next.startMs - now) / 60_000
            if (inMin > 12 * 60) return@GlanceProvider null
            val zone = services.clock.zone()
            val detail = when {
                next.startMs <= now -> "now"
                inMin < 60 -> "in $inMin min"
                else -> hm.format(Instant.ofEpochMilli(next.startMs).atZone(zone))
            }
            GlanceCard("agenda", Icons.Event, next.title, detail, appId = "agenda", priority = if (abs(inMin) < 30) 45 else 20)
        }
    }
}
