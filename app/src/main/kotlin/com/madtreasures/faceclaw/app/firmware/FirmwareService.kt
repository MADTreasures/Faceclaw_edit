package com.madtreasures.faceclaw.app.firmware

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
import com.madtreasures.faceclaw.core.firmware.FlashState
import com.madtreasures.faceclaw.core.firmware.InstallState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process and the CPU alive while firmware work runs (an update must not be cut off
 * by the phone going to sleep) and shows its progress as a notification.
 */
class FirmwareService : LifecycleService() {
    companion object {
        private const val CHANNEL = "firmware-update"
        private const val NOTIFICATION_ID = 4712
        private const val ACTION_STOP = "com.madtreasures.faceclaw.FIRMWARE_STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 45 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FirmwareService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FirmwareService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP || intent == null) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (!observing) {
            observing = true
            val firmware = FaceclawApp.graph(this).firmware
            lifecycleScope.launch {
                firmware.state.collect { st ->
                    val (text, progress) = describe(st)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun describe(st: InstallState): Pair<String, Int?> = when (st) {
        is InstallState.Downloading -> "Downloading the firmware…" to st.total.takeIf { it > 0 }?.let { (st.downloaded * 100 / it).toInt() }
        is InstallState.Patching -> "Building the custom firmware…" to null
        is InstallState.Checking -> "Checking the glasses…" to null
        is InstallState.Flashing -> when (val f = st.state) {
            is FlashState.Flashing -> "Updating the ${f.progress.arm.name.lowercase()} lens — keep the glasses nearby" to (f.progress.fraction * 100).roundToInt()
            is FlashState.Connecting -> "Connecting to the ${f.arm.name.lowercase()} lens…" to null
            is FlashState.Retrying -> "Retrying the ${f.arm.name.lowercase()} lens…" to null
            FlashState.Rebooting -> "Glasses restarting…" to null
            FlashState.Done -> "Update written" to 100
        }
        InstallState.Verifying -> "Checking the new firmware…" to null
        is InstallState.Finished -> "Finished" to null
        InstallState.Idle -> "Preparing…" to null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceclawEdit:FirmwareUpdate").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
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
            NotificationChannel(CHANNEL, getString(R.string.channel_firmware), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_firmware_description)
                setShowBadge(false)
            },
        )
    }

    private fun notification(text: String, progress: Int?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_glasses)
        .setContentTitle("Glasses firmware")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .apply { if (progress != null) setProgress(100, progress.coerceIn(0, 100), false) else setProgress(0, 0, true) }
        .setContentIntent(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
}
