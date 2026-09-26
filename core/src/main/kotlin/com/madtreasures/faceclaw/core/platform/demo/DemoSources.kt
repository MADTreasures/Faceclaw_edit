package com.madtreasures.faceclaw.core.platform.demo

import com.madtreasures.faceclaw.core.platform.CalendarEvent
import com.madtreasures.faceclaw.core.platform.CalendarSource
import com.madtreasures.faceclaw.core.platform.Clock
import com.madtreasures.faceclaw.core.platform.Heading
import com.madtreasures.faceclaw.core.platform.MediaController
import com.madtreasures.faceclaw.core.platform.MediaState
import com.madtreasures.faceclaw.core.platform.NotificationAction
import com.madtreasures.faceclaw.core.platform.NotificationSource
import com.madtreasures.faceclaw.core.platform.PhoneNotification
import com.madtreasures.faceclaw.core.platform.SensorSource
import com.madtreasures.faceclaw.core.platform.WeatherCondition
import com.madtreasures.faceclaw.core.platform.WeatherNow
import com.madtreasures.faceclaw.core.platform.WeatherSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.math.sin

/** Sample data for the simulator and the phone app's demo mode. */
class DemoNotifications(private val clock: Clock) : NotificationSource {
    private val _active = MutableStateFlow<List<PhoneNotification>>(emptyList())
    override val active: StateFlow<List<PhoneNotification>> = _active
    private val _posted = MutableSharedFlow<PhoneNotification>(extraBufferCapacity = 8)
    override val posted: SharedFlow<PhoneNotification> = _posted
    private var counter = 0
    val log = ArrayList<String>()

    private val samples = listOf(
        Triple("Messages", "Anna", "Bist du schon unterwegs? Wir sind im Café am See, draussen auf der Terrasse."),
        Triple("Mail", "Build server", "Nightly build #1284 passed. 312 tests, 0 failures, coverage 81%."),
        Triple("Calendar", "Standup in 10 minutes", "Room Säntis · Video link in the invite"),
        Triple("Signal", "Luca", "Photos from yesterday are in the shared album 📷"),
        Triple("Weather", "Rain expected", "Light rain starting around 17:40 in Zürich."),
    )

    fun seed(count: Int = 3) {
        repeat(count) { post(emit = false) }
    }

    fun post(emit: Boolean = true): PhoneNotification {
        val (app, title, text) = samples[counter % samples.size]
        counter++
        val n = PhoneNotification(
            key = "demo-$counter",
            packageName = "demo.${app.lowercase()}",
            appName = app,
            title = title,
            text = text,
            postedAtMs = clock.nowMs() - (if (emit) 0 else (samples.size - counter) * 7 * 60_000L),
            actions = if (app == "Messages" || app == "Signal") listOf(NotificationAction("reply", "Reply", acceptsText = true), NotificationAction("read", "Mark as read")) else listOf(NotificationAction("archive", "Archive")),
        )
        _active.update { listOf(n) + it }
        if (emit) _posted.tryEmit(n)
        return n
    }

    override fun dismiss(key: String) {
        log += "dismiss $key"
        _active.update { list -> list.filter { it.key != key } }
    }

    override fun dismissAll() {
        log += "dismiss all"
        _active.value = emptyList()
    }

    override fun act(key: String, actionId: String, replyText: String?) {
        log += "act $key $actionId ${replyText ?: ""}".trim()
    }
}

class DemoMedia(private val clock: Clock) : MediaController {
    private val tracks = listOf(
        Triple("Midnight City", "M83", "Hurry Up, We're Dreaming" to 243_000L),
        Triple("Nightcall", "Kavinsky", "OutRun" to 258_000L),
        Triple("Intro", "The xx", "xx" to 128_000L),
    )
    private var index = 0
    private val _state = MutableStateFlow<MediaState?>(stateFor(0, true, 64_000))
    override val state: StateFlow<MediaState?> = _state

    private fun stateFor(i: Int, playing: Boolean, pos: Long): MediaState {
        val (title, artist, albumLen) = tracks[i]
        return MediaState("Spotify", title, artist, albumLen.first, albumLen.second, pos, clock.nowMs(), playing)
    }

    override fun playPause() {
        val s = _state.value ?: return
        _state.value = s.copy(playing = !s.playing, positionMs = s.positionAt(clock.nowMs()), positionSampledAtMs = clock.nowMs())
    }

    override fun next() {
        index = (index + 1) % tracks.size
        _state.value = stateFor(index, true, 0)
    }

    override fun previous() {
        index = (index + tracks.size - 1) % tracks.size
        _state.value = stateFor(index, true, 0)
    }

    override fun volumeUp() {}
    override fun volumeDown() {}
    override fun seekTo(positionMs: Long) {
        _state.update { it?.copy(positionMs = positionMs, positionSampledAtMs = clock.nowMs()) }
    }
}

class DemoWeather(private val clock: Clock) : WeatherSource {
    override val current: StateFlow<WeatherNow?> = MutableStateFlow(
        WeatherNow(18.4, WeatherCondition.PartlyCloudy, "Partly cloudy", 21.0, 12.0, "Zürich", clock.nowMs()),
    )
    override fun refresh() {}
}

class DemoCalendar(clock: Clock) : CalendarSource {
    override val upcoming: StateFlow<List<CalendarEvent>> = MutableStateFlow(
        run {
            val now = clock.nowMs()
            val h = 3_600_000L
            listOf(
                CalendarEvent("1", "Design review", now + 25 * 60_000, now + 85 * 60_000, location = "Room Säntis"),
                CalendarEvent("2", "Lunch with Anna", now + 3 * h, now + 4 * h, location = "Café am See"),
                CalendarEvent("3", "Dentist", now + 26 * h, now + 27 * h),
            )
        },
    )
}

class DemoSensors : SensorSource {
    override val heading: Flow<Heading> = flow {
        var t = 0.0
        while (true) {
            emit(Heading(((t * 12 + 30 * sin(t / 3)) % 360 + 360).toFloat() % 360, 3, System.currentTimeMillis()))
            t += 0.25
            delay(250)
        }
    }
}
