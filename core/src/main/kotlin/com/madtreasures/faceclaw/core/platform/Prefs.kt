package com.madtreasures.faceclaw.core.platform

/** Catalogue of user settings shared by the glasses UI and the phone app. */
object Prefs {
    enum class DisplayArea(val label: String, val width: Int, val height: Int) {
        Full("Full 640×480", 640, 480),
        Comfort("Comfort 600×400", 600, 400),
        Compact("Compact 576×288", 576, 288),
    }

    enum class WakeTarget { Home, LastScreen }
    enum class QuickAction { Notifications, Assistant, DisplayOff, Music }

    // display
    val displayArea = Setting.Choice("display.area", DisplayArea.Full, DisplayArea.values())
    val displayOffsetY = Setting.Int("display.offsetY", 0, -96..96)
    val displayTimeoutSec = Setting.Int("display.timeoutSec", 20, 0..600)
    val brightness = Setting.Int("display.brightness", 60, 0..100)
    val autoBrightness = Setting.Bool("display.autoBrightness", true)
    val animations = Setting.Bool("display.animations", true)
    val wakeTarget = Setting.Choice("display.wakeTarget", WakeTarget.Home, WakeTarget.values())

    // clock
    val clock24h = Setting.Bool("clock.24h", true)
    val clockSeconds = Setting.Bool("clock.seconds", false)

    // input
    val invertRing = Setting.Bool("input.invertRing", false)
    val invertTouchpad = Setting.Bool("input.invertTouchpad", false)
    val quickAction = Setting.Choice("input.quickAction", QuickAction.Notifications, QuickAction.values())
    val sounds = Setting.Bool("input.sounds", false)

    // notifications
    val notificationPopups = Setting.Bool("notifications.popups", true)
    val notificationWake = Setting.Bool("notifications.wakeDisplay", true)
    val notificationPopupSec = Setting.Int("notifications.popupSec", 6, 2..30)
    /** Comma separated package names whose notifications are ignored. */
    val notificationMuted = Setting.Str("notifications.muted", "")

    // teleprompter
    val teleprompterText = Setting.Str("teleprompter.text", "")
    val teleprompterSpeed = Setting.Int("teleprompter.speed", 40, 5..200)

    // weather
    val weatherUnitsCelsius = Setting.Bool("weather.celsius", true)

    // connection (phone side)
    val pairedLeft = Setting.Str("glasses.left", "")
    val pairedRight = Setting.Str("glasses.right", "")
    val pairedName = Setting.Str("glasses.name", "")
    val autoConnect = Setting.Bool("glasses.autoConnect", true)
}
