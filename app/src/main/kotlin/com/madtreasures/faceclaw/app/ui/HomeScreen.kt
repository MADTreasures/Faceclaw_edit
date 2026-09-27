package com.madtreasures.faceclaw.app.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.core.protocol.SessionPhase
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputEvent
import com.madtreasures.faceclaw.core.ui.InputSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    resumeTick: Int,
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFirmware: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
) {
    val status by graph.connection.status.collectAsStateWithLifecycle()
    val frame by graph.preview.collectAsStateWithLifecycle()
    val displayOn by graph.displayOn.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val setup = remember(resumeTick) { Setup.items(ctx) }
    val send = { g: Gesture -> graph.runtime.shell.dispatch(InputEvent(g, InputSource.Phone, System.currentTimeMillis())) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Faceclaw Edit", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionCard(graph, status.phase, status.detail, status.battery, status.firmware?.extension, onOpenPairing, onOpenFirmware)
            GlassesPreview(frame, displayOn, send, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalButton(onClick = { send(Gesture.ScrollUp) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
                FilledTonalButton(onClick = { send(Gesture.ScrollDown) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
                FilledTonalButton(onClick = { send(Gesture.Tap) }) { Text("Tap") }
                FilledTonalButton(onClick = { send(Gesture.DoubleTap) }) { Text("Back") }
                FilledTonalButton(onClick = { send(Gesture.LongPress) }) { Text("Menu") }
            }
            Text(
                "Tap, double-tap, hold or swipe on the preview — it works like the ring, with or without glasses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val missing = setup.filter { !it.granted }
            if (missing.isNotEmpty()) {
                SetupCard(missing, onRequestPermissions) { item ->
                    try {
                        item.settingsIntent?.invoke(ctx)?.let { ctx.startActivity(it) }
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionCard(
    graph: AppGraph,
    phase: SessionPhase,
    detail: String,
    battery: Int?,
    cfw: String?,
    onOpenPairing: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val paired = graph.connection.hasPairedGlasses
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when (phase) {
                SessionPhase.Connected -> "Connected"
                SessionPhase.Connecting -> "Connecting…"
                SessionPhase.Retrying -> "Reconnecting…"
                SessionPhase.Charging -> "Charging"
                SessionPhase.IncompatibleFirmware -> "Firmware not supported"
                SessionPhase.Stopped -> if (paired) "Not connected" else "No glasses paired"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val info = listOfNotNull(
                detail.takeIf { it.isNotBlank() && it != title },
                battery?.let { "Glasses $it%" },
                cfw,
            ).joinToString("  ·  ")
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (phase == SessionPhase.IncompatibleFirmware) {
                Text(
                    "These glasses need the Faceclaw custom firmware (revision 34 or newer). You can install it from the firmware page.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !paired -> Button(onClick = onOpenPairing) { Text("Pair glasses") }
                    graph.connection.pausedForFirmware -> Button(onClick = onOpenFirmware) { Text("Firmware update…") }
                    phase == SessionPhase.IncompatibleFirmware -> {
                        Button(onClick = onOpenFirmware) { Text("Install firmware…") }
                        TextButton(onClick = { graph.connection.connect() }) { Text("Retry") }
                    }
                    graph.connection.hasSession -> OutlinedButton(onClick = { graph.connection.disconnect() }) { Text("Disconnect") }
                    else -> {
                        Button(onClick = { graph.connection.connect() }) { Text("Connect") }
                        TextButton(onClick = onOpenPairing) { Text("Pair other glasses") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(items: List<SetupItem>, onRequest: (List<String>) -> Unit, onOpenSettings: (SetupItem) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (item in items) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (item.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(item.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { if (item.permissions.isNotEmpty()) onRequest(item.permissions) else onOpenSettings(item) }) { Text("Allow") }
                }
            }
        }
    }
}
