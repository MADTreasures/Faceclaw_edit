package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.platform.PhoneNotification
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.InputEvent
import com.madtreasures.faceclaw.core.ui.InputMapper
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.Theme
import com.madtreasures.faceclaw.core.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * The window manager of the glasses UI: runs apps, keeps their screen stacks, draws the
 * status bar and overlays, routes input, manages display power and produces frames.
 *
 * Everything inside runs on [uiScope], which must be single-threaded. Other threads talk
 * to the shell through [dispatch] and [post].
 */
class Shell(
    val services: Services,
    private val uiScope: CoroutineScope,
    val registry: AppRegistry,
    theme: Theme = Theme.Default,
    val width: Int = DisplaySpec.WIDTH,
    val height: Int = DisplaySpec.HEIGHT,
) {
    companion object {
        const val HOME = "home"
        const val LAUNCHER = "launcher"
        const val NOTIFICATIONS = "notifications"
        private const val MIN_FRAME_INTERVAL_MS = 33L
        private const val RESUME_WINDOW_MS = 30_000L
    }

    var theme: Theme = theme
        private set

    private val sinks = CopyOnWriteArrayList<FrameSink>()
    private val back = GrayBitmap(width, height)
    private var front = GrayBitmap(width, height)
    private var lastFrame: Frame? = null
    private var sequence = 0L
    private var lastRenderAt = 0L

    /** Time of the frame being rendered or the input being handled. */
    var frameTimeMs: Long = services.clock.nowMs()
        private set

    private val running = ArrayList<RunningApp>()
    private var foreground: RunningApp? = null
    private val overlays = ArrayList<Overlay>()
    private val mapper = InputMapper()

    var displayOn: Boolean = true
        private set
    private var displayOffAt = 0L
    private var lastInputAt = 0L

    private var renderJob: Job? = null
    private var wakeupJob: Job? = null
    private var started = false

    // ------------------------------------------------------------------ public API (any thread)

    /** Delivers an input event from any thread. */
    fun dispatch(e: InputEvent) {
        uiScope.launch { handleInput(e) }
    }

    /** Runs [block] on the UI thread. */
    fun post(block: Shell.() -> Unit) {
        uiScope.launch { block() }
    }

    fun addFrameSink(sink: FrameSink) {
        sinks += sink
        uiScope.launch {
            lastFrame?.let { sink.onFrame(Frame(it.bitmap, it.bitmap.bounds, it.sequence, it.timeMs)) }
            sink.onDisplayPower(displayOn)
        }
    }

    fun removeFrameSink(sink: FrameSink) {
        sinks -= sink
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (started) return
        started = true
        applyTheme()
        uiScope.launch {
            services.settings.flow(Prefs.animations).drop(1).collect { applyTheme(); invalidate() }
        }
        uiScope.launch {
            services.notifications.posted.collect { onNotificationPosted(it) }
        }
        uiScope.launch { services.notifications.active.collect { invalidate() } }
        uiScope.launch { services.media.state.collect { invalidate() } }
        uiScope.launch { services.status.collect { onStatusChanged(); invalidate() } }
        launchApp(HOME, asForeground = true)
        lastInputAt = services.clock.nowMs()
        invalidate()
    }

    fun stop() {
        for (app in running.toList()) stopApp(app)
        uiScope.cancel()
    }

    private fun applyTheme() {
        val animations = services.settings[Prefs.animations]
        theme = theme.copy(motion = theme.motion.copy(enabled = animations))
        mapper.invertRing = services.settings[Prefs.invertRing]
        mapper.invertTouchpad = services.settings[Prefs.invertTouchpad]
    }

    fun setTheme(t: Theme) {
        theme = t
        applyTheme()
        invalidate()
    }

    // ------------------------------------------------------------------ apps & navigation

    val foregroundAppId: String? get() = foreground?.info?.id
    val runningAppIds: List<String> get() = running.map { it.info.id }
    val topScreen: Screen? get() = foreground?.stack?.lastOrNull()

    fun openApp(id: String) {
        val existing = running.firstOrNull { it.info.id == id }
        if (existing != null) {
            bringToFront(existing)
        } else {
            launchApp(id, asForeground = true)
        }
        invalidate()
    }

    private fun launchApp(id: String, asForeground: Boolean) {
        val info = registry[id] ?: return
        val app = info.factory()
        val scope = CoroutineScope(uiScope.coroutineContext + SupervisorJob(uiScope.coroutineContext[Job]))
        val ra = RunningApp(info, app, scope)
        running += ra
        app.start(ra.ui)
        val root = app.createRootScreen()
        ra.stack += root
        root.attach(ra.ui)
        if (asForeground) bringToFront(ra, resumed = false)
    }

    private fun bringToFront(ra: RunningApp, resumed: Boolean = true) {
        val prev = foreground
        if (prev === ra) return
        prev?.stack?.lastOrNull()?.onHide()
        running.remove(ra)
        running += ra
        foreground = ra
        if (resumed) ra.app.onResume()
        ra.stack.lastOrNull()?.onShow()
        // Leaving an app that asked to be closed on exit ends it.
        if (prev != null && prev.info.closeOnExit && prev.info.id != HOME) stopApp(prev)
    }

    fun goHome() {
        val home = running.firstOrNull { it.info.id == HOME }
        if (home == null) launchApp(HOME, asForeground = true) else bringToFront(home)
        invalidate()
    }

    fun closeApp(id: String) {
        val ra = running.firstOrNull { it.info.id == id } ?: return
        if (id == HOME) return
        val wasForeground = foreground === ra
        if (wasForeground) {
            ra.stack.lastOrNull()?.onHide()
            foreground = null
        }
        stopApp(ra)
        if (wasForeground) goHome()
        invalidate()
    }

    private fun stopApp(ra: RunningApp) {
        for (s in ra.stack.asReversed()) s.detach()
        ra.stack.clear()
        runCatching { ra.app.stop() }
        ra.scope.cancel()
        running.remove(ra)
        if (foreground === ra) foreground = null
    }

    private fun push(ra: RunningApp, screen: Screen) {
        val isFront = foreground === ra
        if (isFront) ra.stack.lastOrNull()?.onHide()
        ra.stack += screen
        screen.attach(ra.ui)
        if (isFront) screen.onShow()
        invalidate()
    }

    private fun pop(ra: RunningApp) {
        if (ra.stack.size <= 1) {
            if (ra.info.id != HOME) {
                if (ra.info.closeOnExit) closeApp(ra.info.id) else goHome()
            }
            return
        }
        val top = ra.stack.removeAt(ra.stack.size - 1)
        if (foreground === ra) top.onHide()
        top.detach()
        if (foreground === ra) ra.stack.lastOrNull()?.onShow()
        invalidate()
    }

    private fun replace(ra: RunningApp, screen: Screen) {
        val top = ra.stack.removeLastOrNull()
        if (foreground === ra) top?.onHide()
        top?.detach()
        ra.stack += screen
        screen.attach(ra.ui)
        if (foreground === ra) screen.onShow()
        invalidate()
    }

    // ------------------------------------------------------------------ overlays

    fun showOverlay(o: Overlay) {
        o.onDismissRequested = { removeOverlay(it) }
        overlays += o
        invalidate()
    }

    fun removeOverlay(o: Overlay) {
        if (overlays.remove(o)) invalidate()
    }

    fun toast(text: String, icon: Int? = null) {
        overlays.removeAll { it is ToastOverlay }
        showOverlay(ToastOverlay(text, icon).also { it.expiresAtMs = services.clock.nowMs() + 2500 })
    }

    private fun onNotificationPosted(n: PhoneNotification) {
        val s = services.settings
        if (!s[Prefs.notificationPopups]) return
        if (n.isOngoing) return
        val muted = s[Prefs.notificationMuted].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (n.packageName in muted) return
        overlays.removeAll { it is NotificationPopup }
        val popup = NotificationPopup(n) { opened -> openNotification(opened) }
        popup.expiresAtMs = services.clock.nowMs() + s[Prefs.notificationPopupSec] * 1000L
        if (!displayOn) {
            if (!s[Prefs.notificationWake]) return
            setDisplay(true)
        }
        showOverlay(popup)
    }

    /** Opens the notifications app focused on [n]. */
    fun openNotification(n: PhoneNotification) {
        openApp(NOTIFICATIONS)
        val ra = running.firstOrNull { it.info.id == NOTIFICATIONS } ?: return
        (ra.app as? NotificationOpener)?.openNotification(n.key)
    }

    // ------------------------------------------------------------------ display power

    fun setDisplay(on: Boolean) {
        if (displayOn == on) return
        val now = services.clock.nowMs()
        displayOn = on
        if (!on) {
            displayOffAt = now
            overlays.removeAll { it is ToastOverlay || it is NotificationPopup }
        } else {
            lastInputAt = now
            val wake = services.settings[Prefs.wakeTarget]
            if (wake == Prefs.WakeTarget.Home && now - displayOffAt > RESUME_WINDOW_MS) goHome()
        }
        for (s in sinks) s.onDisplayPower(on)
        invalidate()
    }

    private fun onStatusChanged() {
        val wearing = services.status.value.wearing
        if (wearing == false && displayOn) setDisplay(false)
    }

    // ------------------------------------------------------------------ input

    private fun handleInput(e: InputEvent) {
        frameTimeMs = services.clock.nowMs()
        lastInputAt = frameTimeMs
        if (!displayOn) {
            setDisplay(true)
            return
        }
        applyTheme()
        val action = mapper.map(e)
        // overlays, topmost first
        for (o in overlays.asReversed().toList()) {
            val consumed = action != null && o.onAction(action, overlayUi)
            if (consumed) {
                invalidate()
                return
            }
            if (o.modal) return
        }
        val ra = foreground ?: return
        val screen = ra.stack.lastOrNull() ?: return
        if (screen.onGesture(e)) {
            invalidate()
            return
        }
        if (action == null) return
        if (screen.onAction(action)) {
            invalidate()
            return
        }
        when (action) {
            Action.Back -> {
                if (ra.info.id == HOME && ra.stack.size <= 1) setDisplay(false) else pop(ra)
            }
            Action.Menu -> showContextMenu(ra, screen)
            Action.Quick -> quickAction()
            else -> {}
        }
        invalidate()
    }

    private fun quickAction() {
        when (services.settings[Prefs.quickAction]) {
            Prefs.QuickAction.Notifications -> openApp(NOTIFICATIONS)
            Prefs.QuickAction.Music -> openApp("music")
            Prefs.QuickAction.DisplayOff -> setDisplay(false)
            Prefs.QuickAction.Assistant -> if (registry["assistant"] != null) openApp("assistant") else toast("Assistant not available", Icons.SmartToy)
        }
    }

    private fun showContextMenu(ra: RunningApp, screen: Screen) {
        val items = ArrayList<MenuItem>()
        items += screen.menuItems()
        if (ra.info.id != HOME) items += MenuItem.Action("Home", Icons.Home) { goHome() }
        val others = running.filter { it !== ra && it.info.id != HOME && it.info.showInLauncher }
        if (others.isNotEmpty()) {
            items += MenuItem.Link("Switch app", Icons.Apps, detail = others.size.toString()) { SwitcherScreen() }
        }
        items += MenuItem.Stepper("Brightness", Icons.BrightnessMedium, 0..100, 10, format = { "$it%" },
            get = { services.settings[Prefs.brightness] },
            set = {
                services.settings[Prefs.brightness] = it
                services.settings[Prefs.autoBrightness] = false
                services.glasses.setBrightness(it, false)
            })
        if (ra.info.id != HOME && ra.info.id != LAUNCHER) {
            items += MenuItem.Action("Close ${ra.info.name}", Icons.Close) { closeApp(ra.info.id) }
        }
        items += MenuItem.Action("Display off", Icons.PowerSettingsNew) { setDisplay(false) }
        showOverlay(MenuOverlay(null, items))
    }

    /** Lists running apps (opened from the context menu). */
    private inner class SwitcherScreen : Screen() {
        private val list = com.madtreasures.faceclaw.core.ui.widgets.MenuList()
        override val title: String get() = "Running apps"
        override fun onShow() {
            list.setItems(running.asReversed().filter { it.info.id != HOME && it.info.showInLauncher }.map { ra ->
                MenuItem.Action(ra.info.name, ra.info.icon, key = ra.info.id) { ui.pop(); openApp(ra.info.id) }
            })
        }
        override fun render(g: Canvas, bounds: IntRect) = list.render(g, bounds, ui)
        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    // ------------------------------------------------------------------ rendering

    /** Requests a new frame soon. Cheap to call repeatedly. */
    fun invalidate() {
        if (renderJob?.isActive == true) return
        renderJob = uiScope.launch {
            val wait = lastRenderAt + MIN_FRAME_INTERVAL_MS - services.clock.nowMs()
            if (wait > 0) delay(wait)
            renderFrame()
        }
    }

    /** Area of the framebuffer the UI uses (display-area setting), always inside the panel. */
    fun viewport(): IntRect {
        val area = services.settings[Prefs.displayArea]
        val w = min(area.width, width)
        val h = min(area.height, height)
        val x = (width - w) / 2
        val y = ((height - h) / 2 + services.settings[Prefs.displayOffsetY]).coerceIn(0, height - h)
        return IntRect.of(x, y, w, h)
    }

    /** Renders synchronously (tests and the simulator's screenshot tool use this). */
    fun renderNow(): Frame? {
        renderFrame()
        return lastFrame
    }

    private fun renderFrame() {
        val now = services.clock.nowMs()
        frameTimeMs = now
        lastRenderAt = now
        overlays.removeAll { o -> o.expiresAtMs?.let { it <= now } == true }
        val g = Canvas(back)
        g.clear(0)
        var animating = false
        if (displayOn) {
            val vp = viewport()
            val ra = foreground
            val screen = ra?.stack?.lastOrNull()
            if (screen != null) {
                val sp = theme.spacing
                val content = if (screen.fullscreen) vp else {
                    StatusBar.render(g, IntRect(vp.left + sp.xl, vp.top + sp.xs, vp.right - sp.xl, vp.top + sp.xs + sp.statusBarHeight), ra.ui, screen.title)
                    vp.dropTop(sp.statusBarHeight + sp.s)
                }
                g.withSave {
                    val inner = if (screen.fullscreen) content else content.inset(sp.xl, 0, sp.xl, sp.l)
                    clipRect(content)
                    runCatching { screen.render(this, inner) }.onFailure { t -> renderError(this, inner, t) }
                }
                animating = runCatching { screen.isAnimating(now) }.getOrDefault(false)
            }
            for (o in overlays.toList()) {
                if (o.modal) g.dimRect(vp, 0.3f)
                g.withSave {
                    clipRect(vp)
                    runCatching { o.render(this, vp, overlayUi) }
                }
                animating = animating || o.isAnimating(now)
            }
        }
        val dirty = back.diffBounds(front)
        if (!dirty.isEmpty || lastFrame == null) {
            front = back.copy()
            val frame = Frame(front, if (lastFrame == null) front.bounds else dirty, ++sequence, now)
            lastFrame = frame
            for (s in sinks) runCatching { s.onFrame(frame) }
        }
        if (animating && displayOn) invalidate()
        scheduleWakeup(now)
    }

    private fun renderError(g: Canvas, r: IntRect, t: Throwable) {
        g.clear(0)
        g.drawText("Render error", r.left.toFloat(), (r.top + 30).toFloat(), theme.type.bodyStrong, theme.levels.textStrong)
        g.drawText(t.toString().take(60), r.left.toFloat(), (r.top + 60).toFloat(), theme.type.caption, theme.levels.textDim)
    }

    /** Plans the next time something changes without input: clock minute, refresh, expiry, auto-off. */
    private fun scheduleWakeup(now: Long) {
        var next = Long.MAX_VALUE
        if (displayOn) {
            val zoneOffset = services.clock.zone().rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds * 1000L
            val local = now + zoneOffset
            next = min(next, now + (60_000 - local % 60_000))
            topScreen?.refreshIntervalMs?.let { next = min(next, now + max(50L, it - (now % it))) }
            overlays.forEach { o -> o.expiresAtMs?.let { next = min(next, it) } }
            val timeoutSec = services.settings[Prefs.displayTimeoutSec]
            val awake = topScreen?.keepAwake == true || overlays.any { it.keepAwake }
            if (timeoutSec > 0 && !awake) {
                val offAt = lastInputAt + timeoutSec * 1000L
                if (offAt <= now) {
                    uiScope.launch { setDisplay(false) }
                    return
                }
                next = min(next, offAt)
            }
        }
        wakeupJob?.cancel()
        if (next == Long.MAX_VALUE) return
        wakeupJob = uiScope.launch {
            delay(max(0L, next - services.clock.nowMs()))
            invalidate()
        }
    }

    // ------------------------------------------------------------------ Ui implementations

    private inner class RunningApp(val info: AppInfo, val app: GlassApp, val scope: CoroutineScope) {
        val stack = ArrayList<Screen>()
        val ui: Ui = AppUi(this)
    }

    private inner class AppUi(private val ra: RunningApp) : Ui {
        override val theme: Theme get() = this@Shell.theme
        override val services: Services get() = this@Shell.services
        override val nowMs: Long get() = frameTimeMs
        override val scope: CoroutineScope get() = ra.scope
        override fun invalidate() = this@Shell.invalidate()
        override fun push(screen: Screen) = push(ra, screen)
        override fun pop() = pop(ra)
        override fun replace(screen: Screen) = replace(ra, screen)
        override fun toast(text: String, icon: Int?) = this@Shell.toast(text, icon)
        override fun openApp(id: String) = this@Shell.openApp(id)
        override fun goHome() = this@Shell.goHome()
        override fun closeApp() = this@Shell.closeApp(ra.info.id)
        override fun showMenu(title: String?, items: List<MenuItem>) = showOverlay(MenuOverlay(title, items))
        override fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) =
            showOverlay(ConfirmOverlay(title, message, confirmLabel, onConfirm))
    }

    /** Ui handed to overlays: navigation acts on the foreground app. */
    private val overlayUi: Ui = object : Ui {
        override val theme: Theme get() = this@Shell.theme
        override val services: Services get() = this@Shell.services
        override val nowMs: Long get() = frameTimeMs
        override val scope: CoroutineScope get() = uiScope
        override fun invalidate() = this@Shell.invalidate()
        override fun push(screen: Screen) { foreground?.let { push(it, screen) } }
        override fun pop() { foreground?.let { pop(it) } }
        override fun replace(screen: Screen) { foreground?.let { replace(it, screen) } }
        override fun toast(text: String, icon: Int?) = this@Shell.toast(text, icon)
        override fun openApp(id: String) = this@Shell.openApp(id)
        override fun goHome() = this@Shell.goHome()
        override fun closeApp() { foreground?.let { closeApp(it.info.id) } }
        override fun showMenu(title: String?, items: List<MenuItem>) = showOverlay(MenuOverlay(title, items))
        override fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) =
            showOverlay(ConfirmOverlay(title, message, confirmLabel, onConfirm))
    }
}

/** Implemented by the notifications app so popups can deep-link into it. */
interface NotificationOpener {
    fun openNotification(key: String)
}
