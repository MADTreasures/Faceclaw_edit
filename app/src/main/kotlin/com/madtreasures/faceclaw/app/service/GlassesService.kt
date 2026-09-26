package com.madtreasures.faceclaw.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.madtreasures.faceclaw.app.FaceclawApp
import com.madtreasures.faceclaw.app.R
import com.madtreasures.faceclaw.app.ui.MainActivity
import com.madtreasures.faceclaw.core.protocol.SessionPhase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and with it the BLE session and the glasses UI)
 * alive while the phone is locked. After a process restart it reconnects by itself.
 */
class GlassesService : LifecycleService() {
    companion object {
        private const val CHANNEL = "glasses-connection"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.madtreasures.faceclaw.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GlassesService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GlassesService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting to the glasses…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // e.g. the Bluetooth permission was revoked while the service was scheduled for restart
            stopSelf()
            return START_NOT_STICKY
        }
        val graph = FaceclawApp.graph(this)
        if (intent == null) graph.connection.connect() // restarted by the system
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                graph.connection.status.collect { st ->
                    val text = when (st.phase) {
                        SessionPhase.Connected -> "Connected" + (st.battery?.let { " · glasses $it%" } ?: "")
                        else -> st.detail.ifBlank { st.phase.name }
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
            lifecycleScope.launch {
                combine(graph.connection.status, graph.displayOn) { st, on -> st.phase == SessionPhase.Connected && on }
                    .distinctUntilChanged()
                    .collect { if (it) acquireWakeLock() else releaseWakeLock() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /** Keeps the CPU running between BLE packets while the glasses display is on. */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceclawEdit:GlassesDisplay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_connection_description)
                setShowBadge(false)
            },
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_glasses)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
}
