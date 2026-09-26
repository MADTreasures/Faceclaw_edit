package com.madtreasures.faceclaw.app.platform

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.madtreasures.faceclaw.app.FaceclawApp
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.platform.NotificationAction
import com.madtreasures.faceclaw.core.platform.NotificationSource
import com.madtreasures.faceclaw.core.platform.PhoneNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Mirrors phone notifications. The listener service feeds this source; the glasses UI reads
 * it and sends back dismissals, actions and quick replies (with real RemoteInput text).
 */
class AndroidNotificationSource(private val context: Context) : NotificationSource {
    private val _active = MutableStateFlow<List<PhoneNotification>>(emptyList())
    override val active: StateFlow<List<PhoneNotification>> = _active
    private val _posted = MutableSharedFlow<PhoneNotification>(extraBufferCapacity = 16)
    override val posted: SharedFlow<PhoneNotification> = _posted
    private val originals = HashMap<String, Notification>()

    @Volatile var service: NotificationListener? = null

    fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NotificationListener::class.java)
        return flat.split(':').any { it == me.flattenToString() || it == me.flattenToShortString() }
    }

    @Synchronized
    fun resync(list: List<Pair<PhoneNotification, Notification>>) {
        originals.clear()
        list.forEach { originals[it.first.key] = it.second }
        _active.value = list.map { it.first }.sortedByDescending { it.postedAtMs }
    }

    @Synchronized
    fun onPosted(n: PhoneNotification, original: Notification) {
        val existed = originals.containsKey(n.key)
        originals[n.key] = original
        _active.update { list -> (listOf(n) + list.filter { it.key != n.key }).sortedByDescending { it.postedAtMs } }
        // Updates of ongoing notifications (progress, navigation) are not new arrivals.
        if (!(existed && n.isOngoing)) _posted.tryEmit(n)
    }

    @Synchronized
    fun onRemoved(key: String) {
        originals.remove(key)
        _active.update { list -> list.filter { it.key != key } }
    }

    override fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
        onRemoved(key)
    }

    override fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
        _active.value = _active.value.filter { it.isOngoing }
    }

    override fun act(key: String, actionId: String, replyText: String?) {
        val original = synchronized(this) { originals[key] } ?: return
        val index = actionId.toIntOrNull() ?: return
        val action = original.actions?.getOrNull(index) ?: return
        try {
            val inputs = action.remoteInputs
            if (replyText != null && !inputs.isNullOrEmpty()) {
                val intent = Intent()
                val results = Bundle()
                for (ri in inputs) results.putCharSequence(ri.resultKey, replyText)
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(context, 0, intent)
            } else {
                action.actionIntent.send()
            }
        } catch (e: Exception) {
            Log.w("Notifications", "action failed: $e")
        }
    }
}

class NotificationListener : NotificationListenerService() {
    private val source get() = FaceclawApp.graph(applicationContext).notifications

    override fun onListenerConnected() {
        source.service = this
        val list = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        source.resync(list.mapNotNull { sbn -> convert(sbn)?.let { it to sbn.notification } })
        FaceclawApp.graph(applicationContext).media.refresh()
    }

    override fun onListenerDisconnected() {
        source.service = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = convert(sbn) ?: return
        source.onPosted(n, sbn.notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        source.onRemoved(sbn.key)
    }

    private fun convert(sbn: StatusBarNotification): PhoneNotification? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT) return null
        val extras = n.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance <= android.app.NotificationManager.IMPORTANCE_MIN) return null
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val appName = extras.getString("android.substName") ?: runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val actions = n.actions.orEmpty().mapIndexedNotNull { i, a ->
            val t = a.title?.toString()
            if (t.isNullOrBlank() || a.actionIntent == null) null
            else NotificationAction(i.toString(), t, acceptsText = !a.remoteInputs.isNullOrEmpty())
        }
        return PhoneNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postedAtMs = sbn.postTime,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            actions = actions,
            isOngoing = sbn.isOngoing || !sbn.isClearable,
            category = n.category,
            icon = iconGray(sbn, 20),
        )
    }

    /** Small icons are alpha templates: render tinted white and keep the alpha as brightness. */
    private fun iconGray(sbn: StatusBarNotification, size: Int): GrayBitmap? = runCatching {
        val icon = sbn.notification.smallIcon ?: return null
        val pkgContext = createPackageContext(sbn.packageName, 0)
        val d = icon.loadDrawable(pkgContext) ?: return null
        d.mutate().setTint(android.graphics.Color.WHITE)
        d.setTintMode(PorterDuff.Mode.SRC_IN)
        val bmp = createBitmap(size, size)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        GrayBitmap(size, size, ByteArray(size * size) { i -> (px[i] ushr 24).toByte() })
    }.getOrNull()
}
