package com.madtreasures.faceclaw.core.gfx

import kotlin.math.max
import kotlin.math.min

/** Integer rectangle, half-open: [left, right) x [top, bottom). */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun intersect(other: IntRect): IntRect {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r <= l || b <= t) EMPTY else IntRect(l, t, r, b)
    }

    fun intersects(other: IntRect): Boolean = !intersect(other).isEmpty

    fun union(other: IntRect): IntRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> IntRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    fun inset(dx: Int, dy: Int = dx): IntRect = IntRect(left + dx, top + dy, right - dx, bottom - dy)
    fun inset(l: Int, t: Int, r: Int, b: Int): IntRect = IntRect(left + l, top + t, right - r, bottom - b)
    fun offset(dx: Int, dy: Int): IntRect = IntRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Splits off the top [h] pixels. */
    fun takeTop(h: Int): IntRect = IntRect(left, top, right, min(bottom, top + h))
    fun dropTop(h: Int): IntRect = IntRect(left, min(bottom, top + h), right, bottom)
    fun takeBottom(h: Int): IntRect = IntRect(left, max(top, bottom - h), right, bottom)
    fun dropBottom(h: Int): IntRect = IntRect(left, top, right, max(top, bottom - h))
    fun takeLeft(w: Int): IntRect = IntRect(left, top, min(right, left + w), bottom)
    fun dropLeft(w: Int): IntRect = IntRect(min(right, left + w), top, right, bottom)
    fun takeRight(w: Int): IntRect = IntRect(max(left, right - w), top, right, bottom)
    fun dropRight(w: Int): IntRect = IntRect(left, top, max(left, right - w), bottom)

    override fun toString(): String = "IntRect($left,$top ${width}x$height)"

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
        fun of(x: Int, y: Int, w: Int, h: Int) = IntRect(x, y, x + w, y + h)
    }
}

data class Point(val x: Float, val y: Float)

enum class HAlign { Start, Center, End }
enum class VAlign { Top, Center, Bottom }
