package com.madtreasures.faceclaw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.app.ble.GlassesScanner
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.protocol.GlassesPair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(graph: AppGraph, onBack: () -> Unit, onRequestPermissions: (List<String>) -> Unit, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val hasPermission = Setup.bluetoothPermissions().all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val scanner = remember { GlassesScanner(ctx) }
    val pairs by scanner.pairs.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val error by scanner.error.collectAsStateWithLifecycle()

    DisposableEffect(hasPermission) {
        if (hasPermission) {
            // A connected temple stops advertising, so leave the current session first.
            graph.connection.disconnect()
            scanner.start()
        }
        onDispose { scanner.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair glasses") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { scanner.stop(); scanner.start() }) { Icon(Icons.Default.Refresh, "Scan again") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasPermission) {
                Text("Faceclaw Edit needs the Bluetooth permission to find your glasses.")
                Button(onClick = { onRequestPermissions(Setup.bluetoothPermissions()) }) { Text("Allow Bluetooth") }
                return@Column
            }
            Text(
                "Take the glasses out of the case and disconnect them from the Even app (or turn off Bluetooth access for it). " +
                    "Both temples are listed as one pair. The first connection asks Android to pair each temple — accept both dialogs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pairs, key = { it.key }) { pair ->
                    PairRow(pair) {
                        val s = graph.settings
                        s[Prefs.pairedLeft] = pair.left!!.address
                        s[Prefs.pairedRight] = pair.right!!.address
                        s[Prefs.pairedName] = pair.displayName
                        scanner.stop()
                        graph.connection.connect()
                        onPaired()
                    }
                }
            }
        }
    }
}

@Composable
private fun PairRow(pair: GlassesPair, onSelect: () -> Unit) {
    val enabled = pair.complete
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(pair.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val sides = listOfNotNull(pair.left?.let { "left ${it.address}" }, pair.right?.let { "right ${it.address}" }).joinToString("  ·  ")
            Text(sides, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val signal = pair.bestRssi?.let { "signal $it dBm" } ?: "paired earlier"
            Text(
                if (enabled) "Tap to use these glasses · $signal" else "Waiting for the other temple… · $signal",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
