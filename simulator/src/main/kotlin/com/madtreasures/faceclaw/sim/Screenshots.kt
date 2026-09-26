package com.madtreasures.faceclaw.sim

import com.madtreasures.faceclaw.core.gfx.Png
import com.madtreasures.faceclaw.core.platform.Clock
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputEvent
import com.madtreasures.faceclaw.core.ui.InputSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Renders a scripted tour through the UI to PNG files, for documentation and design review. */
object Screenshots {
    private class FixedClock : Clock {
        var now: Long = ZonedDateTime.of(2026, 9, 25, 9, 41, 0, 0, ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        override fun nowMs(): Long = now
        override fun zone(): ZoneId = ZoneId.of("Europe/Zurich")
    }

    fun renderAll(dir: File) {
        dir.mkdirs()
        val clock = FixedClock()
        val host = SimHost(null, clock)
        host.settings[Prefs.animations] = false
        host.settings[Prefs.displayTimeoutSec] = 0
        host.settings[Prefs.notificationPopupSec] = 30
        val shell = host.runtime.shell
        runBlocking {
            withContext(host.dispatcher) {
                host.runtime.start()
                host.timers.start(4 * 60_000L + 32_000L, "Tea")
                delay(50)
                clock.now += 32_000
                suspend fun press(vararg gs: Gesture) {
                    for (g in gs) {
                        shell.dispatch(InputEvent(g, InputSource.Ring, clock.now))
                        delay(20)
                    }
                }
                suspend fun shot(name: String) {
                    delay(20)
                    val frame = shell.renderNow() ?: return
                    File(dir, "$name.png").writeBytes(Png.encode(frame.bitmap, 0x3CFF5A))
                    println("wrote $name.png")
                }
                shot("01-home")
                press(Gesture.ScrollDown)
                shot("02-home-card-focused")
                press(Gesture.ScrollUp, Gesture.Tap)
                shot("03-launcher")
                press(Gesture.Tap)
                shot("04-notifications")
                press(Gesture.Tap)
                shot("05-notification-detail")
                press(Gesture.Tap)
                shot("06-notification-actions")
                press(Gesture.DoubleTap, Gesture.DoubleTap)
                shell.openApp("music")
                shot("07-music")
                shell.openApp("timer")
                shot("08-timer")
                press(Gesture.Tap)
                shot("09-timer-running")
                press(Gesture.DoubleTap)
                shell.openApp("settings")
                press(Gesture.Tap)
                shot("10-settings-display")
                press(Gesture.LongPress)
                shot("11-context-menu")
                press(Gesture.DoubleTap)
                shell.openApp("agenda")
                shot("12-agenda")
                shell.openApp("weather")
                shot("13-weather")
                shell.openApp("compass")
                delay(600)
                shot("14-compass")
                shell.openApp("teleprompter")
                shot("15-teleprompter")
                shell.goHome()
                host.notifications.post()
                delay(50)
                shot("16-notification-popup")
            }
        }
        host.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        host.dispatcher.close()
    }
}
