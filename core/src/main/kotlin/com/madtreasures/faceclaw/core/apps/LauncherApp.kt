package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.shell.AppRegistry
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.shell.Shell
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.MenuList

/** The app list. Running apps carry a dot; the long-press menu can close them. */
class LauncherApp(private val registry: AppRegistry, private val runningIds: () -> List<String>) : GlassApp() {
    override fun createRootScreen(): Screen = LauncherScreen()

    private inner class LauncherScreen : Screen() {
        private val list = MenuList()
        override val title: String get() = "Apps"

        override fun onShow() = rebuild()

        private fun rebuild() {
            val running = runningIds().toSet()
            list.setItems(registry.launcherApps.filter { it.id != Shell.LAUNCHER }.map { info ->
                MenuItem.Action(info.name, info.icon, trailingDot = info.id in running, key = info.id) {
                    ui.openApp(info.id)
                }
            })
        }

        override fun render(g: Canvas, bounds: IntRect) {
            rebuild()
            list.render(g, bounds.dropRight(8), ui)
        }

        override fun onAction(action: Action): Boolean {
            if (action == Action.Back) {
                ui.goHome()
                return true
            }
            return list.onAction(action, ui)
        }

        override fun menuItems(): List<MenuItem> {
            val id = list.selectedItem?.key as? String ?: return emptyList()
            if (id !in runningIds()) return emptyList()
            val name = registry[id]?.name ?: id
            return listOf(MenuItem.Action("Close $name", Icons.Close) {
                ui.services.let { }
                closeRequest?.invoke(id)
            })
        }

        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    /** Set by the runtime so the launcher can close other apps. */
    var closeRequest: ((String) -> Unit)? = null
}
