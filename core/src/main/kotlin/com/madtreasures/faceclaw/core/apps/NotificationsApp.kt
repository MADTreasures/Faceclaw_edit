package com.madtreasures.faceclaw.core.apps

import com.madtreasures.faceclaw.core.gfx.Canvas
import com.madtreasures.faceclaw.core.gfx.HAlign
import com.madtreasures.faceclaw.core.gfx.Icons
import com.madtreasures.faceclaw.core.gfx.IntRect
import com.madtreasures.faceclaw.core.gfx.TextLayout
import com.madtreasures.faceclaw.core.platform.PhoneNotification
import com.madtreasures.faceclaw.core.shell.GlanceCard
import com.madtreasures.faceclaw.core.shell.GlanceProvider
import com.madtreasures.faceclaw.core.shell.GlassApp
import com.madtreasures.faceclaw.core.shell.NotificationOpener
import com.madtreasures.faceclaw.core.shell.Shell
import com.madtreasures.faceclaw.core.ui.Action
import com.madtreasures.faceclaw.core.ui.MenuItem
import com.madtreasures.faceclaw.core.ui.Screen
import com.madtreasures.faceclaw.core.ui.widgets.MenuList
import com.madtreasures.faceclaw.core.ui.widgets.TextPager
import kotlinx.coroutines.launch

/** Phone notifications: list, detail view, dismiss and notification actions (incl. quick replies). */
class NotificationsApp : GlassApp(), NotificationOpener {
    private var pendingOpen: String? = null

    override fun createRootScreen(): Screen = ListScreen()

    override fun openNotification(key: String) {
        pendingOpen = key
        ui.invalidate()
    }

    private inner class ListScreen : Screen() {
        private val list = MenuList()
        override val title: String get() = "Notifications"

        override fun onAttach() {
            ui.scope.launch { ui.services.notifications.active.collect { rebuild(it) } }
        }

        private fun rebuild(items: List<PhoneNotification>) {
            list.setItems(items.map { n ->
                MenuItem.Action(
                    label = TextLayout.sanitize(n.title.ifBlank { n.appName }),
                    subtitle = listOf(n.appName, TextLayout.sanitize(n.text.replace('\n', ' '))).filter { it.isNotBlank() }.joinToString("  ·  "),
                    detail = relativeTime(ui.nowMs - n.postedAtMs),
                    key = n.key,
                ) { ui.push(DetailScreen(n)) }
            })
            ui.invalidate()
        }

        override fun render(g: Canvas, bounds: IntRect) {
            pendingOpen?.let { key ->
                pendingOpen = null
                ui.services.notifications.active.value.firstOrNull { it.key == key }?.let { ui.push(DetailScreen(it)); return }
            }
            if (list.items.isEmpty()) {
                val th = theme
                g.drawIcon(Icons.NotificationsNone, (bounds.centerX - 32).toFloat(), (bounds.centerY - 70).toFloat(), 64, th.type.icons(64), th.levels.textFaint)
                g.drawTextIn("No notifications", IntRect(bounds.left, bounds.centerY + 6, bounds.right, bounds.centerY + 40), th.type.title, th.levels.textDim, HAlign.Center)
                return
            }
            list.render(g, bounds.dropRight(8), ui)
        }

        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)

        override fun menuItems(): List<MenuItem> = buildList {
            val key = list.selectedItem?.key as? String
            if (key != null) add(MenuItem.Action("Dismiss", Icons.Delete) { ui.services.notifications.dismiss(key) })
            if (list.items.isNotEmpty()) add(MenuItem.Action("Dismiss all", Icons.Delete) {
                ui.confirm("Dismiss all?", "All notifications will be cleared on your phone too.", "Dismiss all") {
                    ui.services.notifications.dismissAll()
                }
            })
        }
    }

    private inner class DetailScreen(private val n: PhoneNotification) : Screen() {
        private val pager = TextPager(TextLayout.sanitize(n.text))
        override val title: String get() = n.appName

        override fun render(g: Canvas, bounds: IntRect) {
            val th = theme
            val tf = th.type.title
            val header = bounds.takeTop(tf.lineHeight + 26)
            g.drawTextIn(TextLayout.sanitize(n.title), header.takeTop(tf.lineHeight), tf, th.levels.textStrong)
            g.drawTextIn(relativeTime(ui.nowMs - n.postedAtMs, long = true) + (n.subText?.let { "  ·  $it" } ?: ""),
                IntRect(header.left, header.top + tf.lineHeight, header.right, header.bottom), th.type.caption, th.levels.textFaint)
            val hint = bounds.takeBottom(28)
            pager.render(g, IntRect(bounds.left, header.bottom + 8, bounds.right, hint.top - 6), ui)
            g.drawTextIn("Tap for actions", hint, th.type.caption, th.levels.textFaint)
        }

        override fun onAction(action: Action): Boolean = when (action) {
            Action.Select -> {
                ui.showMenu(null, actions())
                true
            }
            else -> pager.onAction(action, ui)
        }

        override fun menuItems(): List<MenuItem> = actions()

        private fun actions(): List<MenuItem> = buildList {
            for (a in n.actions) {
                if (a.acceptsText) {
                    add(MenuItem.Link(a.title, Icons.Reply) { QuickReplyScreen(n, a.id) })
                } else {
                    add(MenuItem.Action(a.title, Icons.TouchApp) {
                        ui.services.notifications.act(n.key, a.id)
                        ui.toast(a.title, Icons.Check)
                    })
                }
            }
            add(MenuItem.Action("Dismiss", Icons.Delete) {
                ui.services.notifications.dismiss(n.key)
                ui.pop()
            })
        }

        override fun isAnimating(nowMs: Long) = pager.isAnimating(nowMs)
    }

    private inner class QuickReplyScreen(private val n: PhoneNotification, private val actionId: String) : Screen() {
        private val list = MenuList(QUICK_REPLIES.map { text ->
            MenuItem.Action(text, key = text) {
                ui.services.notifications.act(n.key, actionId, text)
                ui.toast("Sent", Icons.Send)
                ui.pop()
            }
        })
        override val title: String get() = "Reply"
        override fun render(g: Canvas, bounds: IntRect) = list.render(g, bounds, ui)
        override fun onAction(action: Action): Boolean = list.onAction(action, ui)
        override fun isAnimating(nowMs: Long) = list.isAnimating(nowMs)
    }

    companion object {
        val QUICK_REPLIES = listOf("OK", "Yes", "No", "On my way", "I'll call you later", "Thanks!")

        fun relativeTime(ageMs: Long, long: Boolean = false): String {
            val s = (ageMs / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> if (long) "just now" else "now"
                s < 3600 -> "${s / 60}m" + if (long) " ago" else ""
                s < 86400 -> "${s / 3600}h" + if (long) " ago" else ""
                else -> "${s / 86400}d" + if (long) " ago" else ""
            }
        }

        val glance = GlanceProvider { services, _ ->
            val active = services.notifications.active.value.filter { !it.isOngoing }
            if (active.isEmpty()) null else {
                val latest = active.first()
                GlanceCard(
                    key = "notifications",
                    icon = Icons.Notifications,
                    title = if (active.size == 1) "${latest.appName}: ${latest.title}" else "${active.size} notifications",
                    detail = if (active.size == 1) null else latest.appName,
                    appId = Shell.NOTIFICATIONS,
                    priority = 30,
                )
            }
        }
    }
}
