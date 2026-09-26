package com.madtreasures.faceclaw.core.ui

/** Physical gestures, as delivered by the glasses (ring, temple touchpads) or emulators. */
enum class Gesture {
    Tap,
    DoubleTap,
    ScrollUp,
    ScrollDown,
    LongPress,
    LongPressRelease,
    /** Tap immediately followed by a long press (custom firmware only). */
    TapThenLong,
}

enum class InputSource { Ring, TempleLeft, TempleRight, Phone, Watch, Keyboard, Unknown }

data class InputEvent(
    val gesture: Gesture,
    val source: InputSource = InputSource.Unknown,
    val timeMs: Long = 0,
)

/** Semantic UI actions that screens respond to. */
enum class Action {
    /** Move focus to the previous item / scroll back. */
    Previous,
    /** Move focus to the next item / scroll forward. */
    Next,
    /** Activate the focused item. */
    Select,
    /** Leave the current screen. */
    Back,
    /** Open the context menu. */
    Menu,
    /** Secondary quick action (e.g. start the assistant). */
    Quick,
}

/** Maps gestures to actions; scroll direction can be inverted per input source. */
class InputMapper(var invertRing: Boolean = false, var invertTouchpad: Boolean = false) {
    fun map(e: InputEvent): Action? {
        val invert = when (e.source) {
            InputSource.Ring -> invertRing
            InputSource.TempleLeft, InputSource.TempleRight -> invertTouchpad
            else -> false
        }
        return when (e.gesture) {
            Gesture.ScrollUp -> if (invert) Action.Next else Action.Previous
            Gesture.ScrollDown -> if (invert) Action.Previous else Action.Next
            Gesture.Tap -> Action.Select
            Gesture.DoubleTap -> Action.Back
            Gesture.LongPress -> Action.Menu
            Gesture.TapThenLong -> Action.Quick
            Gesture.LongPressRelease -> null
        }
    }
}
