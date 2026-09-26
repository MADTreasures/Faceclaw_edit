package com.madtreasures.faceclaw.core.platform

import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.time.ZoneId

/*
 * Ports: everything the glasses UI needs from the outside world. The Android app and the
 * desktop simulator provide implementations; the core never touches platform APIs directly.
 */

interface Clock {
    fun nowMs(): Long
    fun zone(): ZoneId
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

// ------------------------------------------------------------------ notifications

data class NotificationAction(val id: String, val title: String, val acceptsText: Boolean = false)

data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val subText: String? = null,
    val actions: List<NotificationAction> = emptyList(),
    val isOngoing: Boolean = false,
    val category: String? = null,
    /** Small monochrome app icon, if the platform could provide one. */
    val icon: GrayBitmap? = null,
)

interface NotificationSource {
    /** Currently active (not dismissed) notifications, newest first. */
    val active: StateFlow<List<PhoneNotification>>
    /** Newly posted notifications, for popups. */
    val posted: SharedFlow<PhoneNotification>
    fun dismiss(key: String)
    fun dismissAll()
    fun act(key: String, actionId: String, replyText: String? = null)
}

// ------------------------------------------------------------------ media

data class MediaState(
    val sourceApp: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** Wall-clock time at which [positionMs] was sampled, so progress can be extrapolated. */
    val positionSampledAtMs: Long,
    val playing: Boolean,
    val art: GrayBitmap? = null,
) {
    fun positionAt(nowMs: Long): Long =
        if (!playing) positionMs else (positionMs + (nowMs - positionSampledAtMs)).coerceIn(0, maxOf(durationMs, 0))
}

interface MediaController {
    val state: StateFlow<MediaState?>
    fun playPause()
    fun next()
    fun previous()
    fun volumeUp()
    fun volumeDown()
    fun seekTo(positionMs: Long)
}

// ------------------------------------------------------------------ device status

enum class LinkState { Simulated, Disconnected, Scanning, Connecting, Connected }

data class DeviceStatus(
    val link: LinkState = LinkState.Disconnected,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val glassesCharging: Boolean = false,
    val ringBattery: Int? = null,
    val phoneBattery: Int? = null,
    val phoneCharging: Boolean = false,
    /** null when unknown (stock firmware or no wear sensor data yet). */
    val wearing: Boolean? = null,
    val firmwareVersion: String? = null,
    val customFirmware: String? = null,
) {
    val glassesBattery: Int? get() = listOfNotNull(leftBattery, rightBattery).minOrNull()
}

// ------------------------------------------------------------------ weather & calendar

data class WeatherNow(
    val temperatureC: Double,
    val condition: WeatherCondition,
    val description: String,
    val highC: Double? = null,
    val lowC: Double? = null,
    val place: String? = null,
    val updatedAtMs: Long = 0,
)

enum class WeatherCondition { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunderstorm, Unknown }

interface WeatherSource {
    val current: StateFlow<WeatherNow?>
    fun refresh()
}

data class CalendarEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
    val location: String? = null,
)

interface CalendarSource {
    /** Upcoming events (today and tomorrow), sorted by start. */
    val upcoming: StateFlow<List<CalendarEvent>>
}

// ------------------------------------------------------------------ sensors

data class Heading(val degrees: Float, val accuracy: Int, val sampledAtMs: Long)

interface SensorSource {
    /** Compass heading from the glasses (custom firmware) or the phone; empty when unavailable. */
    val heading: Flow<Heading>
}

// ------------------------------------------------------------------ null implementations

object NoNotifications : NotificationSource {
    override val active: StateFlow<List<PhoneNotification>> = MutableStateFlow(emptyList())
    override val posted: SharedFlow<PhoneNotification> = MutableSharedFlow()
    override fun dismiss(key: String) {}
    override fun dismissAll() {}
    override fun act(key: String, actionId: String, replyText: String?) {}
}

object NoMedia : MediaController {
    override val state: StateFlow<MediaState?> = MutableStateFlow(null)
    override fun playPause() {}
    override fun next() {}
    override fun previous() {}
    override fun volumeUp() {}
    override fun volumeDown() {}
    override fun seekTo(positionMs: Long) {}
}

object NoWeather : WeatherSource {
    override val current: StateFlow<WeatherNow?> = MutableStateFlow(null)
    override fun refresh() {}
}

object NoCalendar : CalendarSource {
    override val upcoming: StateFlow<List<CalendarEvent>> = MutableStateFlow(emptyList())
}

object NoSensors : SensorSource {
    override val heading: Flow<Heading> = emptyFlow()
}
