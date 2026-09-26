package com.madtreasures.faceclaw.core.shell

import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.Ui

/** A glasses app: a factory for its root screen plus lifecycle hooks. */
abstract class GlassApp {
    private var appUi: Ui? = null
    val ui: Ui get() = appUi ?: error("app not started")

    fun start(ui: Ui) {
        appUi = ui
        onStart()
    }

    fun stop() {
        onStop()
    }

    abstract fun createRootScreen(): Screen
    open fun onStart() {}
    open fun onStop() {}
    /** Called when the app is brought to the foreground again (not on first start). */
    open fun onResume() {}
}

/** Registry entry describing an app. */
class AppInfo(
    val id: String,
    val name: String,
    val icon: Int,
    val showInLauncher: Boolean = true,
    /** Apps that keep working in the background are not closed when leaving them. */
    val closeOnExit: Boolean = false,
    val factory: () -> GlassApp,
)

/**
 * A small card on the home screen showing live state (now playing, a running timer...).
 * Providers are independent of app instances so cards appear even when the app is closed.
 */
data class GlanceCard(
    val key: String,
    val icon: Int,
    val title: String,
    val detail: String? = null,
    /** 0..1 progress shown as a bar, or null. */
    val progress: Float? = null,
    /** App opened when the card is selected. */
    val appId: String? = null,
    /** Higher shows first. */
    val priority: Int = 0,
)

fun interface GlanceProvider {
    fun card(services: Services, nowMs: Long): GlanceCard?
}

class AppRegistry {
    private val apps = LinkedHashMap<String, AppInfo>()
    private val glances = ArrayList<GlanceProvider>()

    fun register(info: AppInfo) {
        apps[info.id] = info
    }

    fun registerGlance(provider: GlanceProvider) {
        glances += provider
    }

    operator fun get(id: String): AppInfo? = apps[id]
    val all: List<AppInfo> get() = apps.values.toList()
    val launcherApps: List<AppInfo> get() = apps.values.filter { it.showInLauncher }

    fun glanceCards(services: Services, nowMs: Long): List<GlanceCard> =
        glances.mapNotNull { runCatching { it.card(services, nowMs) }.getOrNull() }.sortedByDescending { it.priority }
}
