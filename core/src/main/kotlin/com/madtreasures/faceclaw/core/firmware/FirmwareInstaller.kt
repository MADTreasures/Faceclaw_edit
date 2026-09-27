package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Supplies the stock image, from a cache or Even's CDN. The installer verifies whatever it returns. */
fun interface StockImageSource {
    suspend fun load(onProgress: (downloaded: Long, total: Long) -> Unit): ByteArray
}

sealed class InstallState {
    object Idle : InstallState()
    data class Downloading(val downloaded: Long, val total: Long) : InstallState()
    data class Patching(val applied: Int, val total: Int) : InstallState()
    data class Checking(val step: FirmwarePreflight.Step, val arm: Arm?) : InstallState()
    data class Flashing(val state: FlashState) : InstallState()
    object Verifying : InstallState()
    data class Finished(val result: InstallResult) : InstallState()

    /** True while writing to the glasses: the UI must not allow leaving. */
    val isFlashing: Boolean get() = this is Flashing
}

sealed class InstallResult {
    /** Flashed, and after the restart the glasses reported the expected firmware. */
    data class Installed(val kind: FirmwareKind, val firmware: ReportedFirmware) : InstallResult()

    /** Flashed, but the restarted glasses could not be checked or reported something else. */
    data class Unverified(val kind: FirmwareKind, val firmware: ReportedFirmware?) : InstallResult()

    /** Dry run: image prepared, both temples reachable over the update channel, nothing written. */
    data class DryRunPassed(val kind: FirmwareKind, val firmware: ReportedFirmware, val imageSha256: String) : InstallResult()

    /** Nothing was written: declined, low battery, a risk to accept, or no connection. */
    data class NotStarted(val preflight: PreflightResult) : InstallResult()

    /** Nothing was written: the image could not be downloaded or built. */
    data class PrepareFailed(val message: String) : InstallResult()

    /** Writing failed on [arm]; [flashedLenses] were completed before. */
    data class FlashFailed(val arm: Arm, val flashedLenses: List<Arm>, val message: String) : InstallResult()
}

class VerifyTimings(
    /** Time the glasses get to restart before the first check. */
    val restartDelayMs: Long = 15_000,
    val attempts: Int = 6,
    val retryDelayMs: Long = 15_000,
)

/**
 * Installs the stock or custom firmware: prepare and verify the image, check the glasses and let
 * the wearer confirm on them, flash both lenses, then reconnect and check what they report.
 * One install at a time; progress is published in [state].
 */
class FirmwareInstaller(
    private val link: FirmwareLink,
    private val stock: StockImageSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val otaTimings: OtaTimings = OtaTimings(),
    private val preflightTimings: PreflightTimings = PreflightTimings(),
    private val verifyTimings: VerifyTimings = VerifyTimings(),
    /** Builds the image from the stock bytes; tests replace it to use synthetic images. */
    private val prepare: (FirmwareKind, ByteArray, (Int, Int) -> Unit) -> EvenOtaImage = FirmwareCatalog::prepare,
    private val isAllowed: (EvenOtaImage) -> Boolean = { FirmwareCatalog.kindOf(it.recomputeSha256()) != null },
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private fun preflight() = FirmwarePreflight(link, clock, preflightTimings, log)

    /** Reads the installed firmware and both batteries without changing anything. */
    suspend fun check(): PreflightResult = preflight().probe { step, arm -> _state.value = InstallState.Checking(step, arm) }
        .also { _state.value = InstallState.Idle }

    suspend fun install(
        kind: FirmwareKind,
        prompt: FirmwarePreflight.Prompt,
        allowed: Set<FlashRisk> = emptySet(),
        dryRun: Boolean = false,
    ): InstallResult {
        val result = try {
            runInstall(kind, prompt, allowed, dryRun)
        } catch (e: CancellationException) {
            _state.value = InstallState.Idle
            throw e
        }
        _state.value = InstallState.Finished(result)
        return result
    }

    private suspend fun runInstall(kind: FirmwareKind, prompt: FirmwarePreflight.Prompt, allowed: Set<FlashRisk>, dryRun: Boolean): InstallResult {
        // 1. The image, before touching the glasses (so the prompt does not wait for a download).
        val image = try {
            _state.value = InstallState.Downloading(0, 0)
            val stockBytes = stock.load { done, total -> _state.value = InstallState.Downloading(done, total) }
            prepare(kind, stockBytes) { applied, total -> _state.value = InstallState.Patching(applied, total) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("prepare failed: ${e.message}")
            return InstallResult.PrepareFailed(e.message ?: e.toString())
        }
        if (!isAllowed(image)) return InstallResult.PrepareFailed("The prepared image is not on the allow-list.")
        log("image ready: ${image.sha256}, ${image.components.size} components, ${image.payloadBytes} bytes")

        // 2. Glasses: versions, batteries, risks, confirmation on the lens.
        val pre = preflight().confirm(kind, allowed, if (dryRun) null else prompt) { step, arm ->
            _state.value = InstallState.Checking(step, arm)
        }
        if (pre !is PreflightResult.Approved) return InstallResult.NotStarted(pre)

        val flasher = OtaFlasher(link, image, clock, otaTimings, log, isAllowed)
        if (dryRun) {
            return try {
                flasher.checkLenses { _state.value = InstallState.Flashing(it) }
                InstallResult.DryRunPassed(kind, pre.firmware, image.sha256)
            } catch (e: FlashFailedException) {
                InstallResult.FlashFailed(e.arm, emptyList(), e.message ?: "")
            }
        }

        // 3. Flash. From here on the glasses are being modified.
        try {
            flasher.flash { _state.value = InstallState.Flashing(it) }
        } catch (e: FlashFailedException) {
            log("flash failed on ${e.arm}: ${e.message}")
            return InstallResult.FlashFailed(e.arm, e.flashedLenses, e.message ?: "")
        }

        // 4. Reconnect after the restart and check what the glasses report.
        _state.value = InstallState.Verifying
        delay(verifyTimings.restartDelayMs)
        var reported: ReportedFirmware? = null
        repeat(verifyTimings.attempts) { attempt ->
            if (attempt > 0) delay(verifyTimings.retryDelayMs)
            val probe = preflight().probe()
            if (probe is PreflightResult.Probed) {
                reported = probe.firmware
                if (matches(kind, probe.firmware)) return InstallResult.Installed(kind, probe.firmware)
            }
            _state.value = InstallState.Verifying
        }
        return InstallResult.Unverified(kind, reported)
    }

    private fun matches(kind: FirmwareKind, fw: ReportedFirmware): Boolean = when (kind) {
        FirmwareKind.Custom -> fw.customRevision == FirmwareCatalog.CUSTOM_REVISION
        FirmwareKind.Stock -> fw.extension.isNullOrBlank() &&
            listOfNotNull(fw.leftVersion, fw.rightVersion).all { it.trim() == FirmwareCatalog.STOCK_VERSION }
    }

    fun reset() {
        if (!_state.value.isFlashing) _state.value = InstallState.Idle
    }
}
