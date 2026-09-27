package com.madtreasures.faceclaw.app.firmware

import android.util.Log
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.app.ble.AndroidBleLink
import com.madtreasures.faceclaw.core.firmware.FirmwareInstaller
import com.madtreasures.faceclaw.core.firmware.FirmwareKind
import com.madtreasures.faceclaw.core.firmware.FirmwarePreflight
import com.madtreasures.faceclaw.core.firmware.FlashRisk
import com.madtreasures.faceclaw.core.firmware.InstallResult
import com.madtreasures.faceclaw.core.firmware.InstallState
import com.madtreasures.faceclaw.core.firmware.PreflightResult
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.protocol.Arm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs firmware checks and installs for the paired glasses, one at a time, on the app scope.
 * While it works the normal glasses session is paused, and a foreground service with a wake
 * lock keeps the process alive.
 */
class FirmwareController(private val graph: AppGraph) {
    companion object {
        private const val TAG = "Firmware"

        /** Text of the confirmation on the glasses; item 0 declines, item 1 approves. */
        fun prompt(kind: FirmwareKind) = when (kind) {
            FirmwareKind.Custom -> FirmwarePreflight.Prompt(
                "Install the Faceclaw custom firmware? This voids the warranty.",
                "No, cancel",
                "Yes, install",
            )
            FirmwareKind.Stock -> FirmwarePreflight.Prompt(
                "Reinstall the original Even firmware? Custom features will be removed.",
                "No, cancel",
                "Yes, install",
            )
        }
    }

    val stockCache = StockFirmwareCache(graph.context)

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val _lastCheck = MutableStateFlow<PreflightResult?>(null)
    /** Result of the latest [check] (or of the preflight of the latest install). */
    val lastCheck: StateFlow<PreflightResult?> = _lastCheck.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Detailed log of the latest operation (for troubleshooting on real hardware). */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var job: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(this) {
            _log.value = (_log.value + "${timeFormat.format(Date())} $line").takeLast(400)
        }
    }

    private fun addresses(): Map<Arm, String>? {
        val l = graph.settings[Prefs.pairedLeft]
        val r = graph.settings[Prefs.pairedRight]
        return if (l.isBlank() || r.isBlank() || l.equals(r, ignoreCase = true)) null else mapOf(Arm.Left to l, Arm.Right to r)
    }

    val canRun: Boolean get() = addresses() != null && graph.connection.hasBluetoothPermission() && graph.connection.bluetoothEnabled()

    /** Reads the installed firmware and batteries. */
    fun check() = launch("check") { installer ->
        _lastCheck.value = installer.check()
        _state.value = InstallState.Idle
    }

    private data class Request(val kind: FirmwareKind, val allowed: Set<FlashRisk>, val dryRun: Boolean)

    @Volatile private var lastRequest: Request? = null

    fun install(kind: FirmwareKind, allowed: Set<FlashRisk> = emptySet(), dryRun: Boolean = false) {
        lastRequest = Request(kind, allowed, dryRun)
        launch(if (dryRun) "dry run $kind" else "install $kind") { installer ->
            val result = installer.install(kind, prompt(kind), allowed, dryRun)
            if (result is InstallResult.NotStarted) _lastCheck.value = result.preflight
            log("result: $result")
        }
    }

    /** Repeats the last request (install or dry run) with [risk] accepted by the user. */
    fun acceptRiskAndRetry(risk: FlashRisk) {
        val r = lastRequest ?: return
        install(r.kind, r.allowed + risk, r.dryRun)
    }

    /** Stops a check or an install that has not started writing yet. */
    fun cancel() {
        if (_state.value.isFlashing) return
        job?.cancel()
    }

    /** Clears a finished result. */
    fun dismiss() {
        if (!_busy.value) _state.value = InstallState.Idle
    }

    private fun launch(what: String, block: suspend (FirmwareInstaller) -> Unit) {
        val addresses = addresses() ?: return
        synchronized(this) {
            if (_busy.value) return
            _busy.value = true
            _log.value = emptyList()
        }
        log("start: $what (left ${addresses[Arm.Left]}, right ${addresses[Arm.Right]})")
        var reconnect = false
        job = graph.appScope.launch {
            FirmwareService.start(graph.context)
            try {
                graph.connection.pauseForFirmware()
                val link = AndroidBleLink(graph.context, addresses)
                val installer = FirmwareInstaller(link, stockCache, log = ::log)
                val mirror = launch { installer.state.collect { _state.value = it } }
                try {
                    block(installer)
                } finally {
                    mirror.cancel()
                    runCatching { link.disconnect() }
                }
                // Reconnect unless the glasses are now in a state the session cannot use.
                reconnect = when (val result = (_state.value as? InstallState.Finished)?.result) {
                    is InstallResult.Installed -> result.kind == FirmwareKind.Custom
                    is InstallResult.FlashFailed, is InstallResult.Unverified -> false
                    else -> true
                }
            } catch (e: Exception) {
                log("stopped: ${e.message ?: e}")
                if (!_state.value.isFlashing) _state.value = InstallState.Idle
                reconnect = true
                if (e is CancellationException) throw e
            } finally {
                FirmwareService.stop(graph.context)
                _busy.value = false
                graph.connection.resumeAfterFirmware(reconnect && graph.settings[Prefs.autoConnect])
            }
        }
    }
}
