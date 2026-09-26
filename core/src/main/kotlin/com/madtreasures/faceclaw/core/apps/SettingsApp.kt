package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.Setting
import com.madtreasures.faceclaw.core.platform.Settings
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.MenuList

/** Settings that make sense to change on the glasses; everything else lives in the phone app. */
class SettingsApp(private val version: String) : GlassApp() {
    override fun createRootScreen(): Screen = ListScreen("Settings") { s ->
        listOf(
            MenuItem.Link("Display", Icons.BrightnessMedium) { ListScreen("Display") { displayItems(it) } },
            MenuItem.Link("Clock", Icons.Schedule) { ListScreen("Clock") { clockItems(it) } },
            MenuItem.Link("Input", Icons.TouchApp) { ListScreen("Input") { inputItems(it) } },
            MenuItem.Link("Notifications", Icons.Notifications) { ListScreen("Notifications") { notificationItems(it) } },
            MenuItem.Link("About", Icons.Info) { ListScreen("About") { aboutItems() } },
        ).also { s.hashCode() }
    }

    private fun toggle(label: String, icon: Int?, s: Settings, setting: Setting<Boolean>, onChange: (Boolean) -> Unit = {}) =
        MenuItem.Toggle(label, icon, get = { s[setting] }, set = { s[setting] = it; onChange(it) })

    private fun displayItems(s: Settings): List<MenuItem> = listOf(
        MenuItem.Stepper("Brightness", Icons.BrightnessHigh, 0..100, 10, format = { "$it%" }, get = { s[Prefs.brightness] }, set = {
            s[Prefs.brightness] = it
            ui.services.glasses.setBrightness(it, s[Prefs.autoBrightness])
        }),
        MenuItem.Choice("Screen timeout", Icons.Timer, listOf(10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min", 300 to "5 min", 0 to "Never"),
            get = { s[Prefs.displayTimeoutSec] }, set = { s[Prefs.displayTimeoutSec] = it }),
        MenuItem.Choice("Display area", Icons.CropFree, Prefs.DisplayArea.values().map { it to it.label },
            get = { s[Prefs.displayArea] }, set = { s[Prefs.displayArea] = it }),
        MenuItem.Stepper("Vertical offset", Icons.Tune, -96..96, 8, format = { "$it px" }, get = { s[Prefs.displayOffsetY] }, set = { s[Prefs.displayOffsetY] = it }),
        toggle("Animations", Icons.AutoAwesome, s, Prefs.animations),
        MenuItem.Choice("On wake show", Icons.Visibility, listOf(Prefs.WakeTarget.Home to "Home", Prefs.WakeTarget.LastScreen to "Last screen"),
            get = { s[Prefs.wakeTarget] }, set = { s[Prefs.wakeTarget] = it }),
    )

    private fun clockItems(s: Settings): List<MenuItem> = listOf(
        toggle("24-hour clock", Icons.Schedule, s, Prefs.clock24h),
        toggle("Show seconds", Icons.AvTimer, s, Prefs.clockSeconds),
    )

    private fun inputItems(s: Settings): List<MenuItem> = listOf(
        toggle("Invert ring scrolling", Icons.Tune, s, Prefs.invertRing),
        toggle("Invert touchpad scrolling", Icons.Tune, s, Prefs.invertTouchpad),
        MenuItem.Choice("Tap-then-hold", Icons.Gesture, Prefs.QuickAction.values().map { it to it.name.replace(Regex("(?<=.)([A-Z])"), " $1") },
            get = { s[Prefs.quickAction] }, set = { s[Prefs.quickAction] = it }),
        toggle("Sounds", Icons.VolumeUp, s, Prefs.sounds),
    )

    private fun notificationItems(s: Settings): List<MenuItem> = listOf(
        toggle("Show popups", Icons.NotificationsActive, s, Prefs.notificationPopups),
        toggle("Wake display", Icons.Visibility, s, Prefs.notificationWake),
        MenuItem.Stepper("Popup duration", Icons.Timer, 2..30, 1, format = { "$it s" }, get = { s[Prefs.notificationPopupSec] }, set = { s[Prefs.notificationPopupSec] = it }),
    )

    private fun aboutItems(): List<MenuItem> {
        val st = ui.services.status.value
        return listOf(
            MenuItem.Info("Version", version, Icons.Info),
            MenuItem.Info("Connection", st.link.name, Icons.Bluetooth),
            MenuItem.Info("Glasses battery", listOfNotNull(st.leftBattery?.let { "L $it%" }, st.rightBattery?.let { "R $it%" }).joinToString("  ").ifEmpty { "–" }, Icons.BatteryFull),
            MenuItem.Info("Ring battery", st.ringBattery?.let { "$it%" } ?: "–", Icons.Circle),
            MenuItem.Info("Firmware", st.firmwareVersion ?: "–", Icons.Memory),
            MenuItem.Info("Custom firmware", st.customFirmware ?: "–", Icons.DeveloperMode),
        )
    }

    private inner class ListScreen(private val name: String, private val items: (Settings) -> List<MenuItem>) : Screen() {
        private val list = MenuList()
        override val title: String get() = name
        override fun onShow() = list.setItems(items(ui.services.settings))
        override fun render(g: Canvas, bounds: IntRect) = list.render(g, bounds.dropRight(8), ui)
        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }
}
