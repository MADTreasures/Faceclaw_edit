package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareFlowTest {
    private val prompt = FirmwarePreflight.Prompt("Install? Warranty void.", "No, cancel", "Yes, install")
    private val imageBytes = TestImages.build()
    private val image = EvenOtaImage.parse(imageBytes)

    private fun TestScope.fake() = FakeOtaGlasses { testScheduler.currentTime }
    private fun TestScope.preflight(g: FakeOtaGlasses) = FirmwarePreflight(g, { testScheduler.currentTime })
    private fun TestScope.flasher(g: FakeOtaGlasses, allowed: Boolean = true) =
        OtaFlasher(g, image, { testScheduler.currentTime }, isAllowed = if (allowed) ({ true }) else ({ FirmwareCatalog.kindOf(it.sha256) != null }))

    private fun assertFlashed(g: FakeOtaGlasses, arm: Arm) {
        val last = g.verified.getValue(arm).takeLast(image.components.size)
        assertEquals(image.components.map { it.name }, last.map { it.first }, "$arm components in order")
        for ((c, v) in image.components.zip(last)) assertEquals(image.payload(c).toList(), v.second.toList(), "$arm ${c.name} content")
    }

    // ------------------------------------------------------------------ preflight

    @Test
    fun `preflight reads the glasses and asks on the lens`() = runTest {
        val g = fake()
        g.battery[Arm.Left] = 75
        val r = preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt)
        val ok = assertIs<PreflightResult.Approved>(r)
        assertEquals(75, ok.batteryLeft)
        assertEquals(80, ok.batteryRight)
        assertEquals(InstalledFirmware.Stock, ok.firmware.classify())
        assertEquals(prompt.text, g.promptText)
        assertEquals(1, g.shutdowns)
        assertTrue(g.connected.isEmpty(), "both temples are released")
    }

    @Test
    fun `declining or not answering never approves`() = runTest {
        val g = fake()
        g.promptAnswer = 0
        assertIs<PreflightResult.Declined>(preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt))
        g.promptAnswer = null
        val r = preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt)
        assertEquals(PreflightFailure.NoAnswer, assertIs<PreflightResult.Failed>(r).reason)
        assertTrue(testScheduler.currentTime >= 120_000)
    }

    @Test
    fun `low or unknown battery refuses before asking`() = runTest {
        val g = fake()
        g.battery[Arm.Left] = 29
        val r = assertIs<PreflightResult.LowBattery>(preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt))
        assertEquals(29, r.batteryLeft)
        assertNull(g.promptText)
    }

    @Test
    fun `risks need explicit acceptance`() = runTest {
        val g = fake()
        g.version = "2.3.1.5"
        val r = preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt)
        assertEquals(FlashRisk.NewerStock, assertIs<PreflightResult.NeedsAcceptance>(r).risk)
        assertNull(g.promptText)
        assertIs<PreflightResult.Approved>(preflight(g).confirm(FirmwareKind.Custom, setOf(FlashRisk.NewerStock), prompt))

        g.version = "2.3.0.24"
        g.extension = "Faceclaw/34"
        val again = preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt)
        assertEquals(FlashRisk.AlreadyInstalled, assertIs<PreflightResult.NeedsAcceptance>(again).risk)
        assertIs<PreflightResult.Approved>(preflight(g).confirm(FirmwareKind.Stock, emptySet(), prompt))
    }

    @Test
    fun `pairing waits for the dialog and re-sends after bonding`() = runTest {
        val g = fake()
        g.bonded[Arm.Right] = false
        g.bondAcceptedAt = 20_000
        val steps = ArrayList<FirmwarePreflight.Step>()
        assertIs<PreflightResult.Approved>(preflight(g).confirm(FirmwareKind.Custom, emptySet(), prompt) { s, _ -> steps += s })
        assertTrue(FirmwarePreflight.Step.Pairing in steps)
        assertTrue(testScheduler.currentTime >= 22_000)
    }

    @Test
    fun `an unreachable temple is reported`() = runTest {
        val g = fake()
        g.unreachable += Arm.Left
        val r = assertIs<PreflightResult.Failed>(preflight(g).probe())
        assertEquals(PreflightFailure.Connect, r.reason)
        assertEquals(Arm.Left, r.arm)
    }

    // ------------------------------------------------------------------ flashing

    @Test
    fun `flashes left then right and every component verifies`() = runTest {
        val g = fake()
        val states = ArrayList<FlashState>()
        flasher(g).flash { states += it }
        assertEquals(listOf(Arm.Left, Arm.Right), g.completedLenses)
        assertFlashed(g, Arm.Left)
        assertFlashed(g, Arm.Right)
        assertEquals(1, g.begins[Arm.Left])
        assertEquals(1, g.begins[Arm.Right])
        assertEquals(0, g.controlWritesDuringOta, "nothing but OTA traffic during the update")
        val rebootAt = states.indexOf(FlashState.Rebooting)
        assertTrue(rebootAt > 0 && states.take(rebootAt).all { it !is FlashState.Flashing || it.progress.arm == Arm.Left })
        val last = states.filterIsInstance<FlashState.Flashing>().last().progress
        assertEquals(1f, last.fraction)
        assertEquals(FlashState.Done, states.last())
        assertTrue(states.any { it is FlashState.Connecting && it.arm == Arm.Right })
    }

    @Test
    fun `an explicit NAK resends the same block in place`() = runTest {
        val g = fake()
        g.nakBlocks += 3
        flasher(g).flash()
        assertFlashed(g, Arm.Left)
        assertEquals(1, g.begins[Arm.Left])
        assertEquals(image.components.size, g.verified.getValue(Arm.Left).size)
    }

    @Test
    fun `a lost ack is never answered by resending the block`() = runTest {
        val g = fake()
        g.loseAckOfBlocks += 4 // written by the glasses, but the phone cannot know
        flasher(g).flash()
        // reconnect, fresh BEGIN, all components again — and all of them intact
        assertEquals(2, g.begins[Arm.Left])
        assertFlashed(g, Arm.Left)
        assertFlashed(g, Arm.Right)
    }

    @Test
    fun `a dropped link reconnects and starts the lens over`() = runTest {
        val g = fake()
        g.dropLinkAtBlocks += 5
        flasher(g).flash()
        assertEquals(2, g.begins[Arm.Left])
        assertFlashed(g, Arm.Left)
    }

    @Test
    fun `a failed verification retries the component on the same link`() = runTest {
        val g = fake()
        g.failEnds += 2
        flasher(g).flash()
        assertEquals(1, g.begins[Arm.Left])
        assertFlashed(g, Arm.Left)
    }

    @Test
    fun `a lens that keeps failing stops the whole flash`() = runTest {
        val g = fake()
        g.failEnds += setOf(1, 2, 3)
        val e = assertThrows<FlashFailedException> { flasher(g).flash() }
        assertEquals(Arm.Left, e.arm)
        assertTrue(e.flashedLenses.isEmpty())
        assertEquals(0, g.begins[Arm.Right], "the right lens is not touched")
    }

    @Test
    fun `a failure on the second lens reports the first as done`() = runTest {
        val g = fake()
        g.unreachable += Arm.Right
        val e = assertThrows<FlashFailedException> { flasher(g).flash() }
        assertEquals(Arm.Right, e.arm)
        assertEquals(listOf(Arm.Left), e.flashedLenses)
        assertTrue(testScheduler.currentTime >= 120_000, "the second lens gets its full window")
    }

    @Test
    fun `nothing is written with a small MTU or an unlisted image`() = runTest {
        val g = fake()
        g.mtu = 185
        assertContains(assertThrows<FlashFailedException> { flasher(g).flash() }.message!!, "MTU")
        g.mtu = 512
        assertContains(assertThrows<FlashFailedException> { flasher(g, allowed = false).flash() }.message!!, "allow-list")
        assertEquals(0, g.otaWrites)
    }

    @Test
    fun `a dry run connects both lenses but writes nothing`() = runTest {
        val g = fake()
        flasher(g).checkLenses()
        assertEquals(0, g.otaWrites)
        assertEquals(0, g.begins[Arm.Left])
        assertTrue(g.connected.isEmpty())
    }

    // ------------------------------------------------------------------ installer

    private fun TestScope.installer(g: FakeOtaGlasses, stock: StockImageSource = StockImageSource { p -> p(10, 10); imageBytes }) =
        FirmwareInstaller(
            link = g,
            stock = stock,
            clock = { testScheduler.currentTime },
            prepare = { _, bytes, _ -> EvenOtaImage.parse(bytes) },
            isAllowed = { true },
        )

    @Test
    fun `install end to end`() = runTest {
        val g = fake()
        g.extensionAfterFlash = "Faceclaw/34"
        val inst = installer(g)
        val seen = ArrayList<InstallState>()
        val collector = launch { inst.state.collect { seen += it } }
        val r = inst.install(FirmwareKind.Custom, prompt)
        collector.cancel()
        assertEquals(34, assertIs<InstallResult.Installed>(r).firmware.customRevision)
        assertTrue(seen.any { it is InstallState.Checking })
        assertTrue(seen.any { it is InstallState.Flashing })
        assertTrue(seen.any { it is InstallState.Verifying })
        assertIs<InstallState.Finished>(inst.state.value)
    }

    @Test
    fun `install reports unverified when the glasses say something else`() = runTest {
        val g = fake()
        val r = installer(g).install(FirmwareKind.Custom, prompt)
        assertIs<InstallResult.Unverified>(r)
    }

    @Test
    fun `nothing is written when the install does not start`() = runTest {
        val g = fake()
        g.promptAnswer = 0
        assertIs<PreflightResult.Declined>(assertIs<InstallResult.NotStarted>(installer(g).install(FirmwareKind.Custom, prompt)).preflight)
        val failing = installer(g, StockImageSource { throw java.io.IOException("offline") })
        assertContains(assertIs<InstallResult.PrepareFailed>(failing.install(FirmwareKind.Custom, prompt)).message, "offline")
        val dry = installer(fake()).install(FirmwareKind.Custom, prompt, dryRun = true)
        assertIs<InstallResult.DryRunPassed>(dry)
        assertEquals(0, g.otaWrites)
    }
}
