package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.gfx.IntRect

/** Native resolution of the G2 display when driven through the custom firmware. */
object DisplaySpec {
    const val WIDTH = 640
    const val HEIGHT = 480
}

/** An immutable rendered frame. [dirty] is the region that changed since the previous frame. */
class Frame(val bitmap: GrayBitmap, val dirty: IntRect, val sequence: Long, val timeMs: Long)

/** Receives rendered frames: the glasses link, the phone preview, the simulator window. */
interface FrameSink {
    /** Called on the UI thread. The frame is immutable and may be kept. */
    fun onFrame(frame: Frame)

    /** The shell turned the display off (true = on). */
    fun onDisplayPower(on: Boolean) {}
}
