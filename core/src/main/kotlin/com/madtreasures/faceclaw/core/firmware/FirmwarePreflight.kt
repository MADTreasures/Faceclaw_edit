package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.Envelope
import com.madtreasures.faceclaw.core.protocol.G2Events
import com.madtreasures.faceclaw.core.protocol.G2Messages
import com.madtreasures.faceclaw.core.protocol.GlassesEvent
import com.madtreasures.faceclaw.core.protocol.InboundMessage
import com.madtreasures.faceclaw.core.protocol.ProtoWriter
import com.madtreasures.faceclaw.core.protocol.Sid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EvenHub messages of the on-glasses confirmation. The page stays within the stock container
 * limits (≈280×130 per container) so it works on unmodified firmware too.
 */
object PromptMessages {
    const val TEXT_NAME = "flashwarn"
    const val LIST_NAME = "flashmenu"
    const val TEXT_ID = 1
    const val LIST_ID = 2

    /** Page with a text container and a two-item list; item 0 declines, item 1 approves. */
    fun createPage(magic: Int, text: String, items: List<String>): ByteArray = ProtoWriter.build {
        uint(1, 0)
        uint(2, magic)
        message(3) {
            uint(1, 2)
            message(2) {
                uint(1, 0)
                uint(2, 150)
                uint(3, 280)
                uint(4, 120)
                uint(9, LIST_ID)
                string(10, LIST_NAME)
                message(11) {
                    uint(1, items.size)
                    uint(3, 1)
                    items.forEach { string(4, it) }
                }
                uint(12, 1)
            }
            message(3) {
                uint(1, 0)
                uint(2, 0)
                uint(3, 280)
                uint(4, 130)
                uint(9, TEXT_ID)
                string(10, TEXT_NAME)
                string(12, text)
            }
            uint(5, 10000)
        }
    }

    data class ListSelection(val container: String?, val itemName: String?, val index: Int, val eventType: Int)

    /** Decodes a list event `f13{f1{f2 container, f3 item, f4 index, f5 type}}`; type 0 (click) when absent. */
    fun parseListSelection(msg: InboundMessage): ListSelection? {
        if (msg.sid != Sid.EVENHUB) return null
        val ev = msg.proto?.message(13)?.message(1) ?: return null
        return ListSelection(ev.string(2), ev.string(3), ev.int(4) ?: -1, ev.int(5) ?: 0)
    }
}

/** Something the user has to explicitly accept before an install may go ahead. */
enum class FlashRisk {
    /** The glasses run a newer stock version than the image; installing is an untested downgrade. */
    NewerStock,

    /** The firmware version could not be read. */
    UnknownFirmware,

    /** The requested firmware is already installed. */
    AlreadyInstalled,
}

/** Why a preflight could not complete. */
enum class PreflightFailure {
    /** A temple could not be connected. */
    Connect,

    /** A temple did not confirm the authentication (pairing not accepted?). */
    Authenticate,

    /** The glasses did not answer the session start. */
    NoSession,

    /** The confirmation could not be shown or was not answered in time. */
    NoAnswer,
}

sealed class PreflightResult {
    abstract val firmware: ReportedFirmware?

    /** Result of [FirmwarePreflight.probe]; batteries are null when an arm did not report. */
    data class Probed(override val firmware: ReportedFirmware, val batteryLeft: Int?, val batteryRight: Int?) : PreflightResult()
    data class Approved(override val firmware: ReportedFirmware, val batteryLeft: Int, val batteryRight: Int) : PreflightResult()
    data class Declined(override val firmware: ReportedFirmware) : PreflightResult()

    /** The user must accept [risk] (and start again with it allowed). */
    data class NeedsAcceptance(override val firmware: ReportedFirmware, val risk: FlashRisk) : PreflightResult()
    data class LowBattery(override val firmware: ReportedFirmware, val batteryLeft: Int?, val batteryRight: Int?) : PreflightResult()
    data class Failed(override val firmware: ReportedFirmware?, val reason: PreflightFailure, val arm: Arm?, val detail: String) : PreflightResult()
}

class PreflightTimings(
    val preludeTimeoutMs: Long = 2000,
    val requestTimeoutMs: Long = 3000,
    val selectionTimeoutMs: Long = 120_000,
    val heartbeatIntervalMs: Long = 4000,
)

/**
 * Everything before an update: connect and authenticate both temples (so pairing dialogs show up
 * now, not half-way through), read firmware versions and both batteries, check the risks, and
 * let the wearer confirm on the glasses (physical proof that the right glasses are connected).
 * Leaves both temples disconnected.
 */
class FirmwarePreflight(
    private val link: FirmwareLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timings: PreflightTimings = PreflightTimings(),
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val MIN_BATTERY = 30
    }

    enum class Step { Connecting, Pairing, Reading, Confirming }

    /** Connects and reads versions and batteries only (no prompt). */
    suspend fun probe(onStep: (Step, Arm?) -> Unit = { _, _ -> }): PreflightResult =
        run(target = null, allowed = emptySet(), prompt = null, onStep = onStep)

    /**
     * Full check for installing [target]. [prompt] is the text shown on the glasses and its
     * decline/approve items; null skips the confirmation (dry runs, which write nothing).
     */
    suspend fun confirm(
        target: FirmwareKind,
        allowed: Set<FlashRisk>,
        prompt: Prompt?,
        onStep: (Step, Arm?) -> Unit = { _, _ -> },
    ): PreflightResult = run(target, allowed, prompt, onStep)

    data class Prompt(val text: String, val decline: String, val approve: String)

    private suspend fun run(target: FirmwareKind?, allowed: Set<FlashRisk>, prompt: Prompt?, onStep: (Step, Arm?) -> Unit): PreflightResult {
        val ch = StockChannel(link, clock, log)
        try {
            for (arm in listOf(Arm.Right, Arm.Left)) {
                onStep(Step.Connecting, arm)
                try {
                    link.connect(arm, ota = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return PreflightResult.Failed(null, PreflightFailure.Connect, arm, e.message ?: e.toString())
                }
            }
            for (arm in listOf(Arm.Right, Arm.Left)) {
                onStep(if (link.isBonded(arm) == false) Step.Pairing else Step.Connecting, arm)
                if (!ch.authenticate(arm)) {
                    return PreflightResult.Failed(null, PreflightFailure.Authenticate, arm, "authentication not confirmed")
                }
            }
            onStep(Step.Reading, null)
            val prelude = ch.request(Arm.Right, Sid.DASHBOARD, Envelope.FLAG_REQUEST, timings.preludeTimeoutMs, G2Messages.PRELUDE_MAGIC) { G2Messages.prelude() }
                ?: ch.request(Arm.Right, Sid.DASHBOARD, Envelope.FLAG_REQUEST, timings.preludeTimeoutMs, G2Messages.PRELUDE_MAGIC) { G2Messages.prelude() }
            if (prelude == null) return PreflightResult.Failed(null, PreflightFailure.NoSession, Arm.Right, "prelude not acknowledged")

            val right = readSettings(ch, Arm.Right)
            val left = readSettings(ch, Arm.Left)
            val fw = ReportedFirmware(
                leftVersion = right?.leftVersion ?: left?.leftVersion,
                rightVersion = right?.rightVersion ?: left?.rightVersion,
                extension = (right ?: left)?.firmwareExtension,
            )
            log("firmware ${fw.version} ext=${fw.extension} battery L=${left?.battery} R=${right?.battery}")
            val batteryLeft = left?.battery
            val batteryRight = right?.battery
            if (target == null) return PreflightResult.Probed(fw, batteryLeft, batteryRight)

            val risk = when {
                fw.classify() == InstalledFirmware.Unknown -> FlashRisk.UnknownFirmware
                fw.classify() == InstalledFirmware.NewerStock -> FlashRisk.NewerStock
                isAlreadyInstalled(target, fw) -> FlashRisk.AlreadyInstalled
                else -> null
            }
            if (risk != null && risk !in allowed) return PreflightResult.NeedsAcceptance(fw, risk)
            if (batteryLeft == null || batteryRight == null || batteryLeft < MIN_BATTERY || batteryRight < MIN_BATTERY) {
                return PreflightResult.LowBattery(fw, batteryLeft, batteryRight)
            }

            onStep(Step.Confirming, null)
            val p = prompt ?: return PreflightResult.Approved(fw, batteryLeft, batteryRight)
            val approved = askOnGlasses(ch, p) ?: return PreflightResult.Failed(fw, PreflightFailure.NoAnswer, Arm.Right, "no selection")
            return if (approved) PreflightResult.Approved(fw, batteryLeft, batteryRight) else PreflightResult.Declined(fw)
        } finally {
            ch.close()
            for (arm in listOf(Arm.Left, Arm.Right)) runCatching { link.disconnect(arm) }
        }
    }

    private fun isAlreadyInstalled(target: FirmwareKind, fw: ReportedFirmware): Boolean {
        val bothBase = listOf(fw.leftVersion, fw.rightVersion).all { it?.trim() == FirmwareCatalog.STOCK_VERSION }
        return when (target) {
            FirmwareKind.Custom -> fw.customRevision == FirmwareCatalog.CUSTOM_REVISION && bothBase
            FirmwareKind.Stock -> fw.extension.isNullOrBlank() && bothBase
        }
    }

    private suspend fun readSettings(ch: StockChannel, arm: Arm): GlassesEvent.Settings? {
        repeat(2) {
            val reply = ch.request(arm, Sid.SETTINGS, Envelope.FLAG_REQUEST, timings.requestTimeoutMs, build = G2Messages::settingsRead)
            val s = reply?.let { G2Events.decode(it, arm == Arm.Right) as? GlassesEvent.Settings }
            if (s != null) return s
        }
        return null
    }

    /** Shows the prompt on the right temple; true = approved, false = declined, null = no answer. */
    private suspend fun askOnGlasses(ch: StockChannel, p: Prompt): Boolean? {
        while (ch.events.tryReceive().isSuccess) Unit
        var created = false
        repeat(2) {
            if (!created) {
                created = ch.request(Arm.Right, Sid.EVENHUB, Envelope.FLAG_REQUEST, timings.requestTimeoutMs) { m ->
                    PromptMessages.createPage(m, p.text, listOf(p.decline, p.approve))
                } != null
            }
        }
        if (!created) return null
        val answer = coroutineScope {
            val heartbeat = launch {
                while (true) {
                    delay(timings.heartbeatIntervalMs)
                    runCatching { ch.send(Arm.Right, Sid.EVENHUB, Envelope.FLAG_REQUEST, G2Messages.heartbeat(ch.allocMagic())) }
                }
            }
            val result = withTimeoutOrNull(timings.selectionTimeoutMs) { awaitSelection(ch.events, p) }
            heartbeat.cancel()
            result
        }
        runCatching { ch.send(Arm.Right, Sid.EVENHUB, Envelope.FLAG_REQUEST, G2Messages.shutdownPage(ch.allocMagic())) }
        return answer
    }

    private suspend fun awaitSelection(events: ReceiveChannel<Pair<Arm, InboundMessage>>, p: Prompt): Boolean {
        while (true) {
            val (_, msg) = events.receive()
            val sel = PromptMessages.parseListSelection(msg) ?: continue
            if (sel.container != PromptMessages.LIST_NAME || sel.eventType != 0) continue
            return sel.index == 1 || sel.itemName == p.approve
        }
    }
}

internal val Arm.label: String get() = if (this == Arm.Left) "left" else "right"
