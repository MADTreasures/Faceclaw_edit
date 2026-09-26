package com.madtreasures.faceclaw.core.protocol

import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputSource

/** Raw ring report metadata forwarded by the custom firmware (for de-duplication). */
data class RingReport(val wireType: Int, val aux: Int, val speed: Int, val tick: Long)

/** Ring battery as reported in settings field 106. */
data class RingBattery(val connected: Boolean, val valid: Boolean, val charging: Boolean, val level: Int?)

/** Everything the phone understands from glasses notifications. */
sealed class GlassesEvent {
    /** A gesture. [type] is the firmware's OsEventType, [source] 1 right temple, 2 ring, 3 left temple, 0 unknown. */
    data class Gesture(val type: Int, val source: Int, val ring: RingReport? = null) : GlassesEvent()

    /** The EvenHub page was closed by the glasses (exit event 5/6/7); it must be re-created. */
    data class PageExit(val type: Int) : GlassesEvent()

    data class Wear(val onHead: Boolean) : GlassesEvent()

    data class Settings(
        val battery: Int?,
        val charging: Boolean?,
        val silentMode: Boolean?,
        val leftVersion: String?,
        val rightVersion: String?,
        /** Custom firmware revision string, e.g. "Faceclaw/34" (field 100); null on stock firmware. */
        val firmwareExtension: String?,
        val ringBattery: RingBattery?,
    ) : GlassesEvent()

    /** Deferred screen wake from the custom firmware (event 1 double tap, 5 head-up). */
    data class WakeRequest(val event: Int, val nonce: Int) : GlassesEvent()

    /** Tap / long press / release while no page is on screen (custom firmware wake lease). */
    data class IdleGesture(val event: Int, val rawSource: Int) : GlassesEvent()

    data class Compass(val headingDegrees: Int) : GlassesEvent()

    /** "Hey Even" and friends: status 1 = wake word. */
    data class EvenAi(val status: Int) : GlassesEvent()

    data class RingBatteryPush(val battery: RingBattery) : GlassesEvent()
}

object G2Events {
    const val CLICK = 0
    const val SCROLL_UP = 1
    const val SCROLL_DOWN = 2
    const val DOUBLE_CLICK = 3
    const val LONG_PRESS = 9
    const val LONG_PRESS_RELEASE = 10
    const val TAP_THEN_LONG = 11
    const val HEAD_UP = 12
    const val RING_PRESS = 14

    /** Decodes a complete inbound message from the given arm; returns null when it is not an event. */
    fun decode(msg: InboundMessage, fromRight: Boolean): GlassesEvent? {
        val p = msg.proto ?: return null
        return when (msg.sid) {
            Sid.EVENHUB -> if (fromRight) decodeEvenHub(p) else null
            Sid.ONBOARDING -> decodeWear(p)
            Sid.SETTINGS -> decodeSettings(p, fromRight)
            Sid.NAVIGATION -> if (fromRight && p.int(1) == 15) p.message(10)?.int(1)?.let { GlassesEvent.Compass(it) } else null
            Sid.EVEN_AI -> if (fromRight && p.int(1) == 1) p.message(3)?.int(1)?.let { GlassesEvent.EvenAi(it) } else null
            else -> null
        }
    }

    private fun decodeEvenHub(p: ProtoMessage): GlassesEvent? {
        val dev = p.message(13) ?: return null
        dev.message(1)?.let { return null } // list containers: not used
        dev.message(2)?.let { text ->
            val type = text.int(3) ?: CLICK
            return if (type == SCROLL_UP || type == SCROLL_DOWN) GlassesEvent.Gesture(type, 0) else null
        }
        val sys = dev.message(3) ?: return null
        val type = sys.int(1) ?: CLICK
        if (type in 5..7) return GlassesEvent.PageExit(type)
        if (type == 4 || type == 8) return null
        val source = sys.int(2) ?: 0
        val ring = sys.bytes(100)?.let { parseRingMeta(it, source) }
        return GlassesEvent.Gesture(type, source, ring)
    }

    private fun parseRingMeta(b: ByteArray, source: Int): RingReport? {
        if (source != 2 || b.size != 12) return null
        if (b.u8(0) != 0x52 || b.u8(1) != 0x49 || b.u8(2) != 1 || b.u8(3) != 1 || b.u8(7) != 0) return null
        return RingReport(b.u8(4), b.u8(5), b.u8(6), b.u32le(8))
    }

    private fun decodeWear(p: ProtoMessage): GlassesEvent? {
        if (p.int(1) != 3) return null
        val ev = p.message(5) ?: return null
        if (ev.int(1) != 1) return null
        return GlassesEvent.Wear(onHead = (ev.int(2) ?: 0) == 1)
    }

    private fun decodeSettings(p: ProtoMessage, fromRight: Boolean): GlassesEvent? {
        p.bytes(102)?.let { b ->
            if (!fromRight || b.size != 6 || b.u8(0) != 0x46 || b.u8(1) != 0x43 || b.u8(2) != 1) return null
            val event = b.u8(3)
            return when (event) {
                1, 5 -> GlassesEvent.WakeRequest(event, b.u16le(4))
                2, 3, 4 -> GlassesEvent.IdleGesture(event, b.u8(4))
                else -> null
            }
        }
        val ringBattery = p.bytes(106)?.let { parseRingBattery(it) }
        val info = p.message(4)
        if (info != null || p.has(100)) {
            return GlassesEvent.Settings(
                battery = info?.int(12),
                charging = info?.int(13)?.let { it > 0 },
                silentMode = info?.int(14)?.let { it > 0 },
                leftVersion = info?.string(5),
                rightVersion = info?.string(6),
                firmwareExtension = p.string(100),
                ringBattery = ringBattery,
            )
        }
        if (ringBattery != null) return GlassesEvent.RingBatteryPush(ringBattery)
        return null
    }

    private fun parseRingBattery(b: ByteArray): RingBattery? {
        if (b.size < 5 || b.u8(0) != 'R'.code || b.u8(1) != 'B'.code || b.u8(2) != 1) return null
        val flags = b.u8(3)
        val level = b.u8(4)
        return RingBattery(
            connected = flags and 1 != 0,
            valid = flags and 2 != 0,
            charging = flags and 4 != 0,
            level = if (level == 255 || flags and 2 == 0) null else level,
        )
    }

    /** Maps a raw gesture to the UI input model; null for events the UI does not use. */
    fun toInput(e: GlassesEvent.Gesture): Pair<Gesture, InputSource>? {
        val g = when (e.type) {
            CLICK -> Gesture.Tap
            SCROLL_UP -> Gesture.ScrollUp
            SCROLL_DOWN -> Gesture.ScrollDown
            DOUBLE_CLICK -> Gesture.DoubleTap
            LONG_PRESS -> Gesture.LongPress
            LONG_PRESS_RELEASE -> Gesture.LongPressRelease
            TAP_THEN_LONG -> Gesture.TapThenLong
            else -> return null
        }
        val s = when (e.source) {
            1 -> InputSource.TempleRight
            3 -> InputSource.TempleLeft
            else -> InputSource.Ring
        }
        return g to s
    }

    fun idleToInput(e: GlassesEvent.IdleGesture): Pair<Gesture, InputSource>? {
        val g = when (e.event) {
            2 -> Gesture.Tap
            3 -> Gesture.LongPress
            4 -> Gesture.LongPressRelease
            else -> return null
        }
        val s = when (e.rawSource) {
            0 -> InputSource.TempleLeft
            1 -> InputSource.TempleRight
            else -> InputSource.Ring
        }
        return g to s
    }
}

/**
 * The custom firmware forwards raw ring reports before the ring's own 100-tick
 * de-duplication, so the phone repeats it on the ring's clock.
 */
class RingInputFilter {
    private var lastTick = 0L

    fun accept(report: RingReport?): Boolean {
        report ?: return true
        val t = report.wireType
        if (t != 8 && t != 10 && lastTick != 0L && ((report.tick - lastTick) and 0xFFFFFFFFL) < 100) return false
        if (t != 10) lastTick = report.tick
        return true
    }

    fun reset() {
        lastTick = 0
    }
}
