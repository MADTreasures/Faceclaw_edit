package com.madtreasures.faceclaw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.app.BuildConfig
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.Setting
import com.madtreasures.faceclaw.core.platform.Settings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onOpenPairing: () -> Unit) {
    val s = graph.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Glasses") {
                val name by s.flow(Prefs.pairedName).collectAsStateWithLifecycle()
                Text(name.ifBlank { "No glasses paired" }, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenPairing) { Text("Pair glasses…") }
                    if (name.isNotBlank()) TextButton(onClick = {
                        graph.connection.disconnect()
                        s[Prefs.pairedLeft] = ""
                        s[Prefs.pairedRight] = ""
                        s[Prefs.pairedName] = ""
                    }) { Text("Forget") }
                }
                SwitchRow("Connect automatically", s, Prefs.autoConnect)
            }
            Section("Display") {
                SliderRow("Brightness", s, Prefs.brightness, 0..100, 10, "%")
                ChoiceRow("Screen timeout", s, Prefs.displayTimeoutSec, listOf(10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min", 300 to "5 min", 0 to "Never"))
                ChoiceRow("Display area", s, Prefs.displayArea, Prefs.DisplayArea.values().map { it to it.label })
                SliderRow("Vertical offset", s, Prefs.displayOffsetY, -96..96, 8, " px")
                SwitchRow("Animations", s, Prefs.animations)
                SwitchRow("24-hour clock", s, Prefs.clock24h)
                SwitchRow("Show seconds", s, Prefs.clockSeconds)
            }
            Section("Notifications") {
                SwitchRow("Show popups on the glasses", s, Prefs.notificationPopups)
                SwitchRow("Wake the display", s, Prefs.notificationWake)
                SliderRow("Popup duration", s, Prefs.notificationPopupSec, 2..30, 1, " s")
            }
            Section("Input") {
                SwitchRow("Invert ring scrolling", s, Prefs.invertRing)
                SwitchRow("Invert touchpad scrolling", s, Prefs.invertTouchpad)
                ChoiceRow("Tap-then-hold opens", s, Prefs.quickAction, Prefs.QuickAction.values().map { it to it.name.replace(Regex("(?<=.)([A-Z])"), " $1") })
                SwitchRow("Sounds", s, Prefs.sounds)
            }
            Section("Teleprompter") {
                val text by s.flow(Prefs.teleprompterText).collectAsStateWithLifecycle()
                var draft by remember(text) { mutableStateOf(text) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Script") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { s[Prefs.teleprompterText] = draft }, enabled = draft != text) { Text("Save") }
                    TextButton(onClick = { draft = ""; s[Prefs.teleprompterText] = "" }) { Text("Clear") }
                }
                Text("Tip: share text from any app to Faceclaw Edit to load it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderRow("Speed", s, Prefs.teleprompterSpeed, 5..200, 5, " px/s")
            }
            Section("Weather") {
                SwitchRow("Celsius", s, Prefs.weatherUnitsCelsius)
            }
            Section("About") {
                Text("Faceclaw Edit ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Independent rebuild of a G2 companion app. GPLv3. Uses the Faceclaw custom firmware (g2flash) on the glasses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, s: Settings, setting: Setting<Boolean>) {
    val v by s.flow(setting).collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().clickable { s[setting] = !v }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = v, onCheckedChange = { s[setting] = it })
    }
}

@Composable
private fun SliderRow(label: String, s: Settings, setting: Setting<Int>, range: IntRange, step: Int, unit: String) {
    val v by s.flow(setting).collectAsStateWithLifecycle()
    var drag by remember(v) { mutableFloatStateOf(v.toFloat()) }
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${drag.roundToInt()}$unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = drag,
            onValueChange = { drag = (it / step).roundToInt() * step.toFloat() },
            onValueChangeFinished = { s[setting] = drag.roundToInt() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun <T> ChoiceRow(label: String, s: Settings, setting: Setting<T>, options: List<Pair<T, String>>) {
    val v by s.flow(setting).collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Column {
            Text(options.firstOrNull { it.first == v }?.second ?: "–", color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, text) in options) {
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        s[setting] = value
                        open = false
                    })
                }
            }
        }
    }
}
