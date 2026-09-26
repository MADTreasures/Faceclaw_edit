package com.madtreasures.faceclaw.core.protocol

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.FontLibrary
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesSessionTest {
    private fun frame(text: String, seed: Int = 0): GrayBitmap {
        val b = GrayBitmap(640, 480)
        val c = Canvas(b)
        c.fillRoundRect(40f + seed, 60f, 300f, 80f, 24f, 60)
        c.strokeRoundRect(40f + seed, 60f, 300f, 80f, 24f, 2f, 255)
        c.drawText(text, 60f, 112f, FontLibrary.get("inter-semibold-24"), 255)
        c.fillCircle(500f, 300f + seed, 40f, 200)
        return b
    }

    private fun TestScope.newSession(fake: FakeGlasses): GlassesSession =
        GlassesSession(fake, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun connectsDrawsAndReceivesInput() = runTest {
        val fake = FakeGlasses(clock = { testScheduler.currentTime })
        val session = newSession(fake)
        val inputs = ArrayList<SessionEvent>()
        backgroundScope.launch { session.events.collect { inputs += it } }
        session.start()
        advanceTimeBy(3000)
        assertEquals(SessionPhase.Connected, session.status.value.phase)
        assertEquals(34, session.status.value.firmware?.revision)
        assertTrue(fake.pageCreated)

        val f1 = frame("Hello glasses")
        session.submitFrame(f1)
        advanceTimeBy(2000)
        assertContentEquals(FrameEncoder.quantize(f1), fake.composition)
        assertEquals(Triple(60 * 98 / 100 + 2, true, 280), fake.brightness)

        val f2 = frame("Second frame", seed = 20)
        session.submitFrame(f2)
        advanceTimeBy(1000)
        assertContentEquals(FrameEncoder.quantize(f2), fake.composition)
        assertTrue(session.status.value.framesPresented >= 2)

        fake.gesture(G2Events.CLICK, 1)
        fake.gesture(G2Events.SCROLL_DOWN, 2)
        advanceTimeBy(100)
        assertEquals(
            listOf(SessionEvent.Input(Gesture.Tap, InputSource.TempleRight), SessionEvent.Input(Gesture.ScrollDown, InputSource.Ring)),
            inputs.filterIsInstance<SessionEvent.Input>(),
        )

        // keep-alive
        val hb = fake.heartbeats
        advanceTimeBy(20_000)
        assertTrue(fake.heartbeats >= hb + 3, "heartbeats: ${fake.heartbeats}")
        assertEquals(SessionPhase.Connected, session.status.value.phase)

        // a NACK triggers go-back-N replay and the frame still arrives
        fake.nackNext = true
        val f3 = frame("After a NACK", seed = 40)
        session.submitFrame(f3)
        advanceTimeBy(3000)
        assertContentEquals(FrameEncoder.quantize(f3), fake.composition)

        // display off fades out, display on fades back in with the latest frame
        session.setDisplayVisible(false)
        advanceTimeBy(500)
        assertEquals(false, fake.brightness?.second)
        val f4 = frame("Wake up", seed = 60)
        session.submitFrame(f4)
        session.setDisplayVisible(true)
        advanceTimeBy(1000)
        assertEquals(true, fake.brightness?.second)
        assertContentEquals(FrameEncoder.quantize(f4), fake.composition)

        backgroundScope.launch { session.stop() }
        advanceTimeBy(10_000)
        assertTrue(fake.cleanedUp)
        assertEquals(SessionPhase.Stopped, session.status.value.phase)
    }

    @Test
    fun lostAcksAreRecovered() = runTest {
        val fake = FakeGlasses(clock = { testScheduler.currentTime })
        fake.dropAckEvery = 5
        val session = newSession(fake)
        session.start()
        advanceTimeBy(3000)
        for (i in 0 until 6) {
            val f = frame("Frame $i", seed = i * 10)
            session.submitFrame(f)
            advanceTimeBy(2500)
            assertContentEquals(FrameEncoder.quantize(f), fake.composition, "frame $i")
        }
        assertEquals(SessionPhase.Connected, session.status.value.phase)
    }

    @Test
    fun reconnectsAfterLinkLossAndResendsKeyframe() = runTest {
        val fake = FakeGlasses(clock = { testScheduler.currentTime })
        val session = newSession(fake)
        session.start()
        advanceTimeBy(3000)
        val f1 = frame("Before")
        session.submitFrame(f1)
        advanceTimeBy(1000)
        fake.dropLink(Arm.Left)
        runCurrent()
        advanceTimeBy(500)
        assertEquals(SessionPhase.Retrying, session.status.value.phase)
        fake.composition.fill(0)
        fake.screen.fill(0)
        advanceTimeBy(8000)
        assertEquals(SessionPhase.Connected, session.status.value.phase)
        advanceTimeBy(2000)
        assertContentEquals(FrameEncoder.quantize(f1), fake.composition)
    }

    @Test
    fun stockFirmwareIsRejectedWithoutCustomTraffic() = runTest {
        val fake = FakeGlasses(firmwareExtension = null, clock = { testScheduler.currentTime })
        val session = newSession(fake)
        session.start()
        advanceTimeBy(5000)
        assertEquals(SessionPhase.IncompatibleFirmware, session.status.value.phase)
        assertEquals(0, fake.presents)
        assertTrue(!fake.pageCreated)
    }

    @Test
    fun olderCustomFirmwareIsRejected() = runTest {
        val fake = FakeGlasses(firmwareExtension = "Faceclaw/30", clock = { testScheduler.currentTime })
        val session = newSession(fake)
        session.start()
        advanceTimeBy(5000)
        assertEquals(SessionPhase.IncompatibleFirmware, session.status.value.phase)
        assertEquals(30, session.status.value.firmware?.revision)
    }
}
