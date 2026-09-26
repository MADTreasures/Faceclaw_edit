package com.madtreasures.faceclaw.core.ui

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.platform.Services
import kotlinx.coroutines.CoroutineScope

/** What the shell offers to screens and apps. All calls happen on the UI thread. */
interface Ui {
    val theme: Theme
    val services: Services
    /** Time of the frame being rendered / the input being handled. */
    val nowMs: Long
    /** Coroutine scope on the UI thread that lives as long as the owning app. */
    val scope: CoroutineScope

    fun invalidate()
    fun push(screen: Screen)
    fun pop()
    fun replace(screen: Screen)
    fun toast(text: String, icon: Int? = null)
    fun openApp(id: String)
    fun goHome()
    fun closeApp()
    fun showMenu(title: String?, items: List<MenuItem>)
    fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit)
}

/**
 * One page of UI. Apps are stacks of screens; the shell draws the status bar and overlays
 * around the top screen and routes input to it.
 */
abstract class Screen {
    private var attachedUi: Ui? = null
    val ui: Ui get() = attachedUi ?: error("${this::class.simpleName} is not attached")
    val isAttached: Boolean get() = attachedUi != null
    protected val theme: Theme get() = ui.theme

    fun attach(ui: Ui) {
        attachedUi = ui
        onAttach()
    }

    fun detach() {
        onDetach()
        attachedUi = null
    }

    /** Shown in the status bar next to the clock. */
    open val title: String? get() = null
    /** Hides the status bar. */
    open val fullscreen: Boolean get() = false
    /** Prevents the display from turning off automatically while visible. */
    open val keepAwake: Boolean get() = false
    /** Re-render periodically while visible (e.g. 1000 for a seconds display). */
    open val refreshIntervalMs: Long? get() = null

    open fun onAttach() {}
    open fun onDetach() {}
    open fun onShow() {}
    open fun onHide() {}

    abstract fun render(g: Canvas, bounds: IntRect)

    /** Semantic input. Return true when handled. */
    open fun onAction(action: Action): Boolean = false

    /** Raw gestures, offered before mapping to actions. Return true to consume. */
    open fun onGesture(e: InputEvent): Boolean = false

    /** Extra entries for this screen's long-press menu. */
    open fun menuItems(): List<MenuItem> = emptyList()

    /** True while an animation is in progress, so the shell keeps rendering frames. */
    open fun isAnimating(nowMs: Long): Boolean = false
}
