package com.madtreasures.faceclaw.core.runtime

import com.madtreasures.faceclaw.core.apps.AgendaApp
import com.madtreasures.faceclaw.core.apps.CompassApp
import com.madtreasures.faceclaw.core.apps.HomeApp
import com.madtreasures.faceclaw.core.apps.LauncherApp
import com.madtreasures.faceclaw.core.apps.MusicApp
import com.madtreasures.faceclaw.core.apps.NotificationsApp
import com.madtreasures.faceclaw.core.apps.SettingsApp
import com.madtreasures.faceclaw.core.apps.TeleprompterApp
import com.madtreasures.faceclaw.core.apps.TimerApp
import com.madtreasures.faceclaw.core.apps.WeatherApp
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.platform.BeepKind
import com.madtreasures.faceclaw.core.platform.DeviceStatus
import com.madtreasures.faceclaw.core.platform.LinkState
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.protocol.GlassesSession
import com.madtreasures.faceclaw.core.protocol.SessionEvent
import com.madtreasures.faceclaw.core.protocol.SessionPhase
import com.madtreasures.faceclaw.core.protocol.SessionStatus
import com.madtreasures.faceclaw.core.services.TimerService
import com.madtreasures.faceclaw.core.shell.AlertOverlay
import com.madtreasures.faceclaw.core.shell.AppInfo
import com.madtreasures.faceclaw.core.shell.AppRegistry
import com.madtreasures.faceclaw.core.shell.Frame
import com.madtreasures.faceclaw.core.shell.FrameSink
import com.madtreasures.faceclaw.core.shell.Shell
import com.madtreasures.faceclaw.core.ui.InputEvent
import com.madtreasures.faceclaw.core.ui.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Registers every built-in app and glance card. Add your own apps here. */
object AppCatalog {
    fun build(timers: TimerService, version: String, shell: () -> Shell): AppRegistry {
        val r = AppRegistry()
        r.register(AppInfo(Shell.HOME, "Home", Icons.Home, showInLauncher = false) { HomeApp(r) })
        r.register(AppInfo(Shell.LAUNCHER, "Apps", Icons.Apps, showInLauncher = false, closeOnExit = true) {
            LauncherApp(r) { shell().runningAppIds }.also { app -> app.closeRequest = { id -> shell().closeApp(id) } }
        })
        r.register(AppInfo(Shell.NOTIFICATIONS, "Notifications", Icons.Notifications) { NotificationsApp() })
        r.register(AppInfo("music", "Music", Icons.MusicNote) { MusicApp() })
        r.register(AppInfo("timer", "Timer", Icons.Timer) { TimerApp(timers) })
        r.register(AppInfo("agenda", "Agenda", Icons.Event) { AgendaApp() })
        r.register(AppInfo("weather", "Weather", Icons.WbSunny) { WeatherApp() })
        r.register(AppInfo("compass", "Compass", Icons.Explore) { CompassApp() })
        r.register(AppInfo("teleprompter", "Teleprompter", Icons.Subject) { TeleprompterApp() })
        r.register(AppInfo("settings", "Settings", Icons.Settings, closeOnExit = true) { SettingsApp(version) })

        r.registerGlance(TimerApp.glance(timers))
        r.registerGlance(MusicApp.glance)
        r.registerGlance(AgendaApp.glance)
        r.registerGlance(NotificationsApp.glance)
        r.registerGlance(WeatherApp.glance)
        return r
    }
}

/**
 * Everything that runs "on the glasses": the shell with its apps plus background services.
 * Hosts (Android service, simulator) create one, then optionally attach a [GlassesSession].
 */
class GlassesRuntime(
    val services: Services,
    private val uiScope: CoroutineScope,
    val timers: TimerService,
    version: String,
) {
    val registry: AppRegistry = AppCatalog.build(timers, version) { shell }
    val shell: Shell = Shell(services, uiScope, registry)

    fun start() {
        shell.start()
        uiScope.launch {
            timers.finished.collect { t ->
                shell.setDisplay(true)
                services.glasses.beep(BeepKind.Alarm)
                shell.showOverlay(
                    AlertOverlay(
                        "Time's up", t.label, Icons.Alarm,
                        listOf(
                            MenuItem.Action("Dismiss", Icons.Check) {},
                            MenuItem.Action("+1 minute", Icons.Snooze) { timers.start(60_000, "${t.label} +1") },
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Connects the shell to real (or simulated) glasses: frames and display power go out,
     * gestures come in. Returns a job that detaches when cancelled.
     */
    fun attach(session: GlassesSession, scope: CoroutineScope): Job {
        val sink = object : FrameSink {
            override fun onFrame(frame: Frame) = session.submitFrame(frame.bitmap)
            override fun onDisplayPower(on: Boolean) = session.setDisplayVisible(on)
        }
        shell.addFrameSink(sink)
        session.setBrightness(services.settings[Prefs.brightness])
        val job = scope.launch {
            launch { services.settings.flow(Prefs.brightness).collect { session.setBrightness(it) } }
            session.events.collect { e ->
                when (e) {
                    is SessionEvent.Input -> shell.dispatch(InputEvent(e.gesture, e.source, services.clock.nowMs()))
                    SessionEvent.Wake -> shell.post { setDisplay(true) }
                    SessionEvent.WakeWord -> shell.post { setDisplay(true) }
                    is SessionEvent.Compass -> {}
                }
            }
        }
        job.invokeOnCompletion { shell.removeFrameSink(sink) }
        return job
    }

    companion object {
        /** Maps the session state onto the status the UI shows. */
        fun deviceStatus(s: SessionStatus, phoneBattery: Int? = null, phoneCharging: Boolean = false): DeviceStatus = DeviceStatus(
            link = when (s.phase) {
                SessionPhase.Connected, SessionPhase.Charging -> LinkState.Connected
                SessionPhase.Connecting -> LinkState.Connecting
                else -> LinkState.Disconnected
            },
            leftBattery = s.battery,
            rightBattery = s.battery,
            glassesCharging = s.charging,
            ringBattery = s.ringBattery?.level,
            phoneBattery = phoneBattery,
            phoneCharging = phoneCharging,
            wearing = s.wearing,
            firmwareVersion = s.firmware?.rightVersion ?: s.firmware?.leftVersion,
            customFirmware = s.firmware?.extension,
        )
    }
}
