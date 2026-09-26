package com.madtreasures.faceclaw.core.protocol

import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G2EventsTest {
    private fun msg(sid: Int, hex: String, flag: Int = Envelope.FLAG_NOTIFY) = InboundMessage(sid, flag, hexBytes(hex))

    @Test
    fun ringReportWithMetadata() {
        // docs/analysis/03-input-sensors-audio.md §2.5 example
        val e = G2Events.decode(msg(Sid.EVENHUB, "08026a151a13080e1002a2060c524901010aabcd00efcdab89"), fromRight = true) as GlassesEvent.Gesture
        assertEquals(14, e.type)
        assertEquals(2, e.source)
        assertEquals(RingReport(10, 0xAB, 0xCD, 0x89ABCDEFL), e.ring)
    }

    @Test
    fun sysEventWithoutSource() {
        val e = G2Events.decode(msg(Sid.EVENHUB, "08026a041a02080e"), fromRight = true) as GlassesEvent.Gesture
        assertEquals(14, e.type)
        assertEquals(0, e.source)
    }

    @Test
    fun tapFromRightTempleMapsToInput() {
        val pb = ProtoWriter.build { uint(1, 2); message(13) { message(3) { uint(1, 0); uint(2, 1) } } }
        val e = G2Events.decode(InboundMessage(Sid.EVENHUB, 1, pb), true) as GlassesEvent.Gesture
        assertEquals(Gesture.Tap to InputSource.TempleRight, G2Events.toInput(e))
    }

    @Test
    fun textScrollEvent() {
        val pb = ProtoWriter.build { uint(1, 2); message(13) { message(2) { uint(1, 1); string(2, "dashboard"); uint(3, 2) } } }
        val e = G2Events.decode(InboundMessage(Sid.EVENHUB, 1, pb), true) as GlassesEvent.Gesture
        assertEquals(Gesture.ScrollDown, G2Events.toInput(e)!!.first)
    }

    @Test
    fun wearEvent() {
        assertEquals(GlassesEvent.Wear(true), G2Events.decode(msg(Sid.ONBOARDING, "080310002a0408011001", 0), false))
        assertEquals(GlassesEvent.Wear(false), G2Events.decode(msg(Sid.ONBOARDING, "080310002a0408011000", 0), false))
    }

    @Test
    fun settingsReplyWithCustomFirmware() {
        val pb = ProtoWriter.build {
            uint(1, 4); uint(2, 101)
            message(4) { string(5, "2.3.0.24"); string(6, "2.3.0.24"); uint(12, 77); uint(13, 0) }
            string(100, "Faceclaw/34")
            bytes(106, byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 1, 3, 55))
        }
        val s = G2Events.decode(InboundMessage(Sid.SETTINGS, 0, pb), true) as GlassesEvent.Settings
        assertEquals(77, s.battery)
        assertEquals(false, s.charging)
        assertEquals("Faceclaw/34", s.firmwareExtension)
        assertEquals(RingBattery(true, true, false, 55), s.ringBattery)
    }

    @Test
    fun idleGestureAndWake() {
        assertEquals(GlassesEvent.IdleGesture(2, 1), G2Events.decode(msg(Sid.SETTINGS, "08031000b20606464301020100", 0), true))
        assertEquals(GlassesEvent.WakeRequest(1, 0x0203), G2Events.decode(msg(Sid.SETTINGS, "08031000b20606464301010302", 0), true))
    }

    @Test
    fun ringFilterDropsReportsWithin100Ticks() {
        val f = RingInputFilter()
        assertTrue(f.accept(RingReport(1, 0, 0, 1000)))
        assertFalse(f.accept(RingReport(1, 0, 0, 1050)))
        assertTrue(f.accept(RingReport(8, 0, 0, 1060)))    // release always passes
        assertTrue(f.accept(RingReport(10, 0, 0, 1061)))   // press never filtered, does not advance
        assertFalse(f.accept(RingReport(4, 0, 0, 1100)))
        assertTrue(f.accept(RingReport(4, 0, 0, 1200)))
        assertTrue(f.accept(null))
    }
}
