package com.madtreasures.faceclaw.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Commands the UI can send to the glasses hardware (no-ops in the simulator). */
interface GlassesControl {
    /** Brightness 0..100; [auto] enables the glasses' ambient-light driven brightness. */
    fun setBrightness(percent: Int, auto: Boolean) {}
    /** Short feedback sound on the glasses' buzzer, when the firmware supports it. */
    fun beep(kind: BeepKind) {}
}

enum class BeepKind { Tick, Confirm, Alert, Alarm }

object NoGlassesControl : GlassesControl

/** Everything apps can reach. Built by the host (Android service or simulator). */
class Services(
    val settings: Settings,
    val clock: Clock = SystemClock,
    val status: StateFlow<DeviceStatus> = MutableStateFlow(DeviceStatus(link = LinkState.Simulated)),
    val notifications: NotificationSource = NoNotifications,
    val media: MediaController = NoMedia,
    val weather: WeatherSource = NoWeather,
    val calendar: CalendarSource = NoCalendar,
    val sensors: SensorSource = NoSensors,
    val glasses: GlassesControl = NoGlassesControl,
)
