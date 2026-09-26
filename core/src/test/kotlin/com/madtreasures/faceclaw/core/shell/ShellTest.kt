package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.platform.Clock
import com.madtreasures.faceclaw.core.platform.MemorySettingsStorage
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.platform.Settings
import com.madtreasures.faceclaw.core.runtime.GlassesRuntime
import com.madtreasures.faceclaw.core.services.TimerService
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShellTest {
    private class RecordingSink : FrameSink {
        val events = ArrayList<String>()
        override fun onFrame(frame: Frame) {
            events += "frame"
        }

        override fun onDisplayPower(on: Boolean) {
            events += if (on) "on" else "off"
        }
    }

    private fun TestScope.runtime(): GlassesRuntime {
        val clock = object : Clock {
            override fun nowMs() = testScheduler.currentTime + 1_750_000_000_000
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        val settings = Settings(MemorySettingsStorage())
        settings[Prefs.animations] = false
        val services = Services(settings = settings, clock = clock)
        return GlassesRuntime(services, backgroundScope, TimerService(clock, backgroundScope), "test")
    }

    @Test
    fun wakeSendsFreshFrameBeforePowerOnAndNothingWhileOff() = runTest {
        val rt = runtime()
        val sink = RecordingSink()
        rt.shell.addFrameSink(sink)
        rt.start()
        advanceTimeBy(500)
        assertTrue(sink.events.contains("frame"))

        rt.shell.post { setDisplay(false) }
        advanceTimeBy(500)
        sink.events.clear()
        rt.services.settings[Prefs.clock24h] = false // would change the frame if it were rendered
        rt.shell.invalidate()
        advanceTimeBy(2000)
        assertEquals(emptyList(), sink.events)

        rt.shell.post { setDisplay(true) }
        advanceTimeBy(500)
        assertEquals("frame", sink.events.first())
        assertTrue(sink.events.indexOf("on") > 0)
    }

    @Test
    fun tapOnHomeOpensLauncherAndDoubleTapGoesBack() = runTest {
        val rt = runtime()
        rt.start()
        advanceTimeBy(500)
        assertEquals(Shell.HOME, rt.shell.foregroundAppId)
        rt.shell.dispatch(InputEvent(Gesture.Tap))
        advanceTimeBy(200)
        assertEquals(Shell.LAUNCHER, rt.shell.foregroundAppId)
        rt.shell.dispatch(InputEvent(Gesture.ScrollDown))
        rt.shell.dispatch(InputEvent(Gesture.Tap))
        advanceTimeBy(200)
        assertEquals("music", rt.shell.foregroundAppId)
        rt.shell.dispatch(InputEvent(Gesture.DoubleTap))
        advanceTimeBy(200)
        assertEquals(Shell.HOME, rt.shell.foregroundAppId)
        rt.shell.dispatch(InputEvent(Gesture.DoubleTap))
        advanceTimeBy(200)
        assertEquals(false, rt.shell.displayOn)
    }
}
