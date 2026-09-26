package com.madtreasures.faceclaw.core.ui

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.IntRect

/** Rows of a [MenuList]. */
sealed class MenuItem {
    abstract val label: String
    open val icon: Int? get() = null
    open val subtitle: String? get() = null
    open val selectable: Boolean get() = true
    /** Stable identity used to keep the selection when the list is rebuilt. */
    open val key: Any get() = label

    /** Runs [onSelect] when activated. [detail] is shown right-aligned. */
    class Action(
        override val label: String,
        override val icon: Int? = null,
        val detail: String? = null,
        override val subtitle: String? = null,
        val trailingDot: Boolean = false,
        override val key: Any = label,
        val onSelect: () -> Unit,
    ) : MenuItem()

    /** Opens a sub-screen. */
    class Link(
        override val label: String,
        override val icon: Int? = null,
        val detail: String? = null,
        override val subtitle: String? = null,
        override val key: Any = label,
        val open: () -> Screen,
    ) : MenuItem()

    class Toggle(
        override val label: String,
        override val icon: Int? = null,
        override val subtitle: String? = null,
        override val key: Any = label,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : MenuItem()

    /** A value from a fixed list. Few options cycle on select; many open a picker. */
    class Choice<T>(
        override val label: String,
        override val icon: Int? = null,
        val options: List<Pair<T, String>>,
        override val key: Any = label,
        val get: () -> T,
        val set: (T) -> Unit,
    ) : MenuItem() {
        fun currentLabel(): String = options.firstOrNull { it.first == get() }?.second ?: "–"
        fun cycle() {
            val i = options.indexOfFirst { it.first == get() }
            set(options[(i + 1).mod(options.size)].first)
        }
    }

    /** A number adjusted with Previous/Next after selecting the row. */
    class Stepper(
        override val label: String,
        override val icon: Int? = null,
        val range: IntRange,
        val step: Int = 1,
        override val key: Any = label,
        val format: (Int) -> String = { it.toString() },
        val get: () -> Int,
        val set: (Int) -> Unit,
    ) : MenuItem()

    /** Non-selectable section title. */
    class Header(override val label: String) : MenuItem() {
        override val selectable: Boolean get() = false
    }

    /** Non-selectable label/value line. */
    class Info(override val label: String, val detail: String? = null, override val icon: Int? = null) : MenuItem() {
        override val selectable: Boolean get() = false
    }

    /** Fully custom row. */
    class Custom(
        override val label: String,
        val height: Int,
        override val key: Any = label,
        override val selectable: Boolean = true,
        val draw: (g: Canvas, row: IntRect, selected: Boolean, theme: Theme) -> Unit,
        val onSelect: () -> Unit = {},
    ) : MenuItem()
}
