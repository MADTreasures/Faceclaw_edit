package com.madtreasures.faceclaw.core.ui

import kotlin.math.pow

fun interface Easing {
    fun apply(t: Float): Float

    companion object {
        val Linear = Easing { it }
        val OutCubic = Easing { 1f - (1f - it).pow(3) }
        val InOutCubic = Easing { if (it < 0.5f) 4f * it * it * it else 1f - (-2f * it + 2f).pow(3) / 2f }
        /** Slight overshoot, used for the menu bounce at list ends. */
        val OutBack = Easing {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            1f + c3 * (it - 1f).pow(3) + c1 * (it - 1f).pow(2)
        }
    }
}

/**
 * A float that animates towards a target over time. Reading [value] with the current frame
 * time yields the interpolated value; [isRunning] tells the shell another frame is needed.
 */
class AnimatedFloat(initial: Float) {
    private var from = initial
    private var to = initial
    private var startMs = 0L
    private var durationMs = 0L
    private var easing: Easing = Easing.OutCubic

    val target: Float get() = to

    fun snapTo(v: Float) {
        from = v
        to = v
        durationMs = 0
    }

    fun animateTo(v: Float, nowMs: Long, duration: Long, easing: Easing = Easing.OutCubic) {
        if (duration <= 0) {
            snapTo(v)
            return
        }
        from = value(nowMs)
        to = v
        startMs = nowMs
        durationMs = duration
        this.easing = easing
    }

    fun value(nowMs: Long): Float {
        if (durationMs <= 0) return to
        val t = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * easing.apply(t)
    }

    fun isRunning(nowMs: Long): Boolean = durationMs > 0 && nowMs - startMs < durationMs
}
