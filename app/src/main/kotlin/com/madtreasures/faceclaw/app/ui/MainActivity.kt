package com.madtreasures.faceclaw.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.madtreasures.faceclaw.app.FaceclawApp
import com.madtreasures.faceclaw.core.platform.Prefs

enum class Page { Home, Pairing, Settings, Firmware }

class MainActivity : ComponentActivity() {
    /** Bumped on resume so permission state is re-read after visiting system settings. */
    private var resumeTick by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
        FaceclawApp.graph(this).media.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ })
        val graph = FaceclawApp.graph(this)
        setContent {
            FaceclawTheme {
                var page by rememberSaveable { mutableStateOf(Page.Home) }
                BackHandler(enabled = page != Page.Home) { page = Page.Home }
                when (page) {
                    Page.Home -> HomeScreen(
                        graph = graph,
                        resumeTick = resumeTick,
                        onOpenPairing = { page = Page.Pairing },
                        onOpenSettings = { page = Page.Settings },
                        onOpenFirmware = { page = Page.Firmware },
                        onRequestPermissions = { permissionLauncher.launch(it.toTypedArray()) },
                    )
                    Page.Pairing -> PairingScreen(
                        graph = graph,
                        onBack = { page = Page.Home },
                        onRequestPermissions = { permissionLauncher.launch(it.toTypedArray()) },
                        onPaired = { page = Page.Home },
                    )
                    Page.Settings -> SettingsScreen(
                        graph = graph,
                        onBack = { page = Page.Home },
                        onOpenPairing = { page = Page.Pairing },
                        onOpenFirmware = { page = Page.Firmware },
                    )
                    Page.Firmware -> FirmwareScreen(graph = graph, onBack = { page = Page.Home })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text shared from other apps becomes the teleprompter script. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        FaceclawApp.graph(this).settings[Prefs.teleprompterText] = text
        Toast.makeText(this, "Sent to the teleprompter", Toast.LENGTH_SHORT).show()
    }
}
