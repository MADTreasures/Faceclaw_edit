package com.madtreasures.faceclaw.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.core.firmware.FirmwareCatalog
import com.madtreasures.faceclaw.core.firmware.FirmwareKind
import com.madtreasures.faceclaw.core.firmware.FirmwarePreflight
import com.madtreasures.faceclaw.core.firmware.FlashRisk
import com.madtreasures.faceclaw.core.firmware.FlashState
import com.madtreasures.faceclaw.core.firmware.InstallResult
import com.madtreasures.faceclaw.core.firmware.InstallState
import com.madtreasures.faceclaw.core.firmware.InstalledFirmware
import com.madtreasures.faceclaw.core.firmware.PreflightFailure
import com.madtreasures.faceclaw.core.firmware.PreflightResult
import com.madtreasures.faceclaw.core.firmware.ReportedFirmware
import com.madtreasures.faceclaw.core.protocol.Arm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareScreen(graph: AppGraph, onBack: () -> Unit) {
    val fw = graph.firmware
    val state by fw.state.collectAsStateWithLifecycle()
    val busy by fw.busy.collectAsStateWithLifecycle()
    val check by fw.lastCheck.collectAsStateWithLifecycle()
    val log by fw.log.collectAsStateWithLifecycle()
    val flashing = state.isFlashing
    var accepted by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    // Leaving is blocked while the lenses are being written; the screen stays on meanwhile.
    BackHandler(enabled = flashing) {}
    val view = LocalView.current
    DisposableEffect(flashing) {
        view.keepScreenOn = flashing
        onDispose { view.keepScreenOn = false }
    }

    val finished = (state as? InstallState.Finished)?.result
    val needs = (finished as? InstallResult.NotStarted)?.preflight as? PreflightResult.NeedsAcceptance

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Glasses firmware") },
                navigationIcon = {
                    if (!flashing) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InstalledCard(check, busy, fw.canRun) { fw.check() }

            when {
                busy -> ProgressCard(state, onCancel = if (flashing) null else ({ fw.cancel() }))
                finished != null && needs == null -> ResultCard(finished, onDone = { fw.dismiss() }, onConnect = {
                    fw.dismiss()
                    graph.connection.connect()
                })
                else -> InstallCard(
                    enabled = fw.canRun,
                    accepted = accepted,
                    onAccepted = { accepted = it },
                    onInstall = { kind -> fw.install(kind) },
                    onDryRun = { fw.install(FirmwareKind.Custom, dryRun = true) },
                )
            }

            if (log.isNotEmpty()) {
                DetailsCard(log, showLog, onToggle = { showLog = !showLog })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (needs != null) {
        AlertDialog(
            onDismissRequest = { fw.dismiss() },
            title = { Text(riskTitle(needs.risk)) },
            text = { Text(riskText(needs.risk, needs.firmware)) },
            confirmButton = { TextButton(onClick = { fw.acceptRiskAndRetry(needs.risk) }) { Text("Continue anyway") } },
            dismissButton = { TextButton(onClick = { fw.dismiss() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InstalledCard(check: PreflightResult?, busy: Boolean, canRun: Boolean, onCheck: () -> Unit) {
    FirmwareSection("On the glasses") {
        val probe = check as? PreflightResult.Probed
        val fw = check?.firmware
        if (fw == null) {
            Text(
                if (check is PreflightResult.Failed) failureText(check) else "Not checked yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(describe(fw), style = MaterialTheme.typography.bodyLarge)
            val versions = listOfNotNull(fw.leftVersion?.let { "left $it" }, fw.rightVersion?.let { "right $it" }).joinToString(" · ")
            if (versions.isNotEmpty()) Muted("Versions: $versions")
            if (probe != null) Muted("Battery: left ${probe.batteryLeft?.let { "$it %" } ?: "?"} · right ${probe.batteryRight?.let { "$it %" } ?: "?"}")
        }
        OutlinedButton(onClick = onCheck, enabled = !busy && canRun) { Text("Check glasses") }
        if (!canRun) Muted("Pair the glasses and turn on Bluetooth first.")
    }
}

@Composable
private fun InstallCard(
    enabled: Boolean,
    accepted: Boolean,
    onAccepted: (Boolean) -> Unit,
    onInstall: (FirmwareKind) -> Unit,
    onDryRun: () -> Unit,
) {
    FirmwareSection("Install") {
        Text(
            "Faceclaw Edit needs the Faceclaw custom firmware (revision ${FirmwareCatalog.CUSTOM_REVISION}, based on Even's ${FirmwareCatalog.STOCK_VERSION}). " +
                "The app downloads Even's original firmware, builds the custom firmware from it on this phone and checks both against pinned fingerprints before anything is written.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Muted(
            "Before you start: glasses charged (at least 30 % per lens, checked automatically), the Even app disconnected, glasses and phone close together. " +
                "Writing takes several minutes per lens. You confirm on the glasses themselves before anything is written.",
        )
        Row(Modifier.fillMaxWidth().clickable { onAccepted(!accepted) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = onAccepted)
            Text(
                "I understand that custom firmware voids the warranty and that a failed update can, in rare cases, leave the glasses unusable.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { onInstall(FirmwareKind.Custom) },
            enabled = enabled && accepted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Install custom firmware") }
        OutlinedButton(
            onClick = { onInstall(FirmwareKind.Stock) },
            enabled = enabled && accepted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reinstall original firmware ${FirmwareCatalog.STOCK_VERSION}") }
        TextButton(onClick = onDryRun, enabled = enabled) { Text("Dry run (prepare and connect, write nothing)") }
    }
}

@Composable
private fun ProgressCard(state: InstallState, onCancel: (() -> Unit)?) {
    FirmwareSection(if (state.isFlashing) "Updating — do not close the app" else "Working…") {
        val (text, fraction) = progressOf(state)
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Muted("${(fraction * 100).toInt()} %")
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.isFlashing) {
            Muted("Keep the glasses on or next to the phone. Both lenses restart between the two halves of the update.")
        }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

private fun progressOf(state: InstallState): Pair<String, Float?> = when (state) {
    is InstallState.Downloading ->
        (if (state.total > 0) "Downloading Even's firmware (${state.downloaded / 1024} of ${state.total / 1024} KB)…" else "Loading Even's firmware…") to
            state.total.takeIf { it > 0 }?.let { state.downloaded.toFloat() / it }
    is InstallState.Patching -> "Building the custom firmware (${state.applied}/${state.total})…" to state.applied.toFloat() / state.total.coerceAtLeast(1)
    is InstallState.Checking -> when (state.step) {
        FirmwarePreflight.Step.Connecting -> "Connecting to the ${armName(state.arm)}…"
        FirmwarePreflight.Step.Pairing -> "Pairing with the ${armName(state.arm)} — accept the Bluetooth request"
        FirmwarePreflight.Step.Reading -> "Reading firmware and battery…"
        FirmwarePreflight.Step.Confirming -> "Confirm on your glasses: scroll to \"Yes, install\" and tap."
    } to null
    is InstallState.Flashing -> when (val f = state.state) {
        is FlashState.Connecting -> "Connecting to the ${armName(f.arm)} for the update…" to null
        is FlashState.Flashing -> "Writing the ${armName(f.progress.arm)}: part ${f.progress.component} of ${f.progress.components}, block ${f.progress.block}/${f.progress.blocks}" to f.progress.fraction
        is FlashState.Retrying -> "Retrying the ${armName(f.arm)} (${f.reason})…" to null
        FlashState.Rebooting -> "Left lens done. The glasses restart; reconnecting for the right lens…" to 0.5f
        FlashState.Done -> "Both lenses written." to 1f
    }
    InstallState.Verifying -> "Waiting for the glasses to restart and checking the new firmware…" to null
    is InstallState.Finished, InstallState.Idle -> "Starting…" to null
}

@Composable
private fun ResultCard(result: InstallResult, onDone: () -> Unit, onConnect: () -> Unit) {
    val (title, text, good) = when (result) {
        is InstallResult.Installed -> Triple(
            "Done",
            if (result.kind == FirmwareKind.Custom) "The glasses now run the Faceclaw custom firmware (${result.firmware.extension}). You can connect." else
                "The glasses run Even's original firmware ${FirmwareCatalog.STOCK_VERSION} again. Faceclaw Edit needs the custom firmware to work.",
            true,
        )
        is InstallResult.Unverified -> Triple(
            "Written, but not confirmed",
            "Both lenses were written, but afterwards the glasses " +
                (result.firmware?.let { "reported ${describe(it)}." } ?: "could not be reached.") +
                " Turn the glasses off and on (or put them in the case briefly), then use \"Check glasses\".",
            false,
        )
        is InstallResult.DryRunPassed -> Triple(
            "Dry run passed",
            "The firmware image was built and verified (SHA-256 ${result.imageSha256.take(16)}…), both lenses were reachable over the update channel. Nothing was written.",
            true,
        )
        is InstallResult.NotStarted -> Triple("Not started", notStartedText(result.preflight) + " Nothing was changed.", false)
        is InstallResult.PrepareFailed -> Triple("Could not prepare the firmware", result.message + "\nNothing was changed.", false)
        is InstallResult.FlashFailed -> Triple(
            "Update failed",
            "Writing the ${armName(result.arm)} failed: ${result.message}\n" +
                (if (result.flashedLenses.isNotEmpty()) "The ${result.flashedLenses.joinToString { armName(it) }} was already updated, so the lenses now differ. " else "") +
                "Keep the glasses charged and try again — the update starts over for both lenses. If the glasses no longer start normally, the official Even app can reinstall its firmware.",
            false,
        )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (good) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result is InstallResult.Installed && result.kind == FirmwareKind.Custom) {
                    Button(onClick = onConnect) { Text("Connect") }
                }
                TextButton(onClick = onDone) { Text("OK") }
            }
        }
    }
}

@Composable
private fun DetailsCard(log: List<String>, open: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    FirmwareSection("Details") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onToggle) { Text(if (open) "Hide log" else "Show log (${log.size} lines)") }
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Firmware log", log.joinToString("\n")))
                Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        }
        if (open) {
            Text(log.takeLast(120).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun FirmwareSection(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun armName(arm: Arm?) = when (arm) {
    Arm.Left -> "left lens"
    Arm.Right -> "right lens"
    null -> "glasses"
}

private fun describe(fw: ReportedFirmware): String = when (fw.classify()) {
    InstalledFirmware.CurrentCustom -> "Faceclaw custom firmware (${fw.extension?.trim()}) on ${fw.version ?: "?"}"
    InstalledFirmware.OlderCustom -> "an older Faceclaw custom firmware (${fw.extension?.trim()})"
    InstalledFirmware.ForeignCustom -> "custom firmware from another project (\"${fw.extension?.trim()}\")"
    InstalledFirmware.Stock -> "Even's original firmware ${fw.version}"
    InstalledFirmware.NewerStock -> "Even's original firmware ${fw.version} (newer than ${FirmwareCatalog.STOCK_VERSION})"
    InstalledFirmware.Unknown -> "an unknown firmware version"
}

private fun failureText(f: PreflightResult.Failed): String = when (f.reason) {
    PreflightFailure.Connect -> "Could not connect to the ${armName(f.arm)}. Make sure the glasses are on and not connected to the Even app or another phone."
    PreflightFailure.Authenticate -> "The ${armName(f.arm)} did not accept the connection. If the phone shows a Bluetooth pairing request, accept it, then try again."
    PreflightFailure.NoSession -> "The glasses did not respond. Turn them off and on and try again."
    PreflightFailure.NoAnswer -> "No answer on the glasses in time."
}

private fun notStartedText(p: PreflightResult): String = when (p) {
    is PreflightResult.Declined -> "You declined on the glasses."
    is PreflightResult.LowBattery ->
        "Charge the glasses first: left ${p.batteryLeft?.let { "$it %" } ?: "unknown"}, right ${p.batteryRight?.let { "$it %" } ?: "unknown"} (at least ${FirmwarePreflight.MIN_BATTERY} % each)."
    is PreflightResult.Failed -> failureText(p)
    is PreflightResult.NeedsAcceptance -> riskTitle(p.risk) + "."
    is PreflightResult.Approved, is PreflightResult.Probed -> ""
}

private fun riskTitle(risk: FlashRisk) = when (risk) {
    FlashRisk.NewerStock -> "Newer firmware on the glasses"
    FlashRisk.UnknownFirmware -> "Firmware version unknown"
    FlashRisk.AlreadyInstalled -> "Already installed"
}

private fun riskText(risk: FlashRisk, fw: ReportedFirmware?) = when (risk) {
    FlashRisk.NewerStock ->
        "The glasses run Even's firmware ${fw?.version ?: "?"}, which is newer than ${FirmwareCatalog.STOCK_VERSION}, the version this app installs. " +
            "Installing is a downgrade that has not been tested. The official Even app can update the glasses again later."
    FlashRisk.UnknownFirmware ->
        "The glasses did not report a firmware version. Only continue if you are sure these are your G2 glasses and they work normally."
    FlashRisk.AlreadyInstalled -> "This firmware is already on the glasses. Install it again anyway?"
}
