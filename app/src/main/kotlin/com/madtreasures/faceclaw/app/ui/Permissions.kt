package com.madtreasures.faceclaw.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.madtreasures.faceclaw.app.FaceclawApp

/** One item of the setup checklist on the home screen. */
data class SetupItem(
    val id: String,
    val title: String,
    val why: String,
    val granted: Boolean,
    /** Runtime permissions to request, or empty for a settings screen. */
    val permissions: List<String> = emptyList(),
    val settingsIntent: ((Context) -> Intent)? = null,
    val required: Boolean = false,
)

object Setup {
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun items(ctx: Context): List<SetupItem> {
        val graph = FaceclawApp.graph(ctx)
        val list = ArrayList<SetupItem>()
        list += SetupItem(
            "bluetooth", "Bluetooth", "Find and connect to the glasses.",
            bluetoothPermissions().all { has(ctx, it) }, permissions = bluetoothPermissions(), required = true,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += SetupItem(
                "post", "Status notification", "Shows that the glasses stay connected in the background.",
                has(ctx, Manifest.permission.POST_NOTIFICATIONS), permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        list += SetupItem(
            "listener", "Notification access", "Mirror notifications and control music on the glasses.",
            graph.notifications.hasAccess(),
            settingsIntent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        )
        // Sideloaded app: the direct exemption dialog is the least confusing way to ask.
        val pm = ctx.getSystemService(PowerManager::class.java)
        list += SetupItem(
            "battery", "Run in the background", "Without this, Android pauses the connection when the phone sleeps.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName),
            settingsIntent = { c -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + c.packageName).toUri()) },
        )
        list += SetupItem(
            "calendar", "Calendar", "Show your next events.",
            has(ctx, Manifest.permission.READ_CALENDAR), permissions = listOf(Manifest.permission.READ_CALENDAR),
        )
        list += SetupItem(
            "location", "Approximate location", "Local weather.",
            has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION), permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        return list
    }
}
