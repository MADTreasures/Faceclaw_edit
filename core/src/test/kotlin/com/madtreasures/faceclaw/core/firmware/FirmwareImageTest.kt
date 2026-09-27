package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Crc16
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FirmwareImageTest {
    private val check = "123456789".toByteArray()

    @Test
    fun `checksums match their check values`() {
        assertEquals(0xC052A8C8L, Crc32cMsb.compute(check))
        assertEquals(0xD7B91914L, Crc32cMsb.compute(ByteArray(256) { it.toByte() }))
        assertEquals(0L, Crc32cMsb.compute(ByteArray(0)))
        assertEquals(0xCBF43926L, ZlibCrc32.compute(check))
        assertEquals(0x29B1, Crc16.compute(check))
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", Digests.sha256(check))
    }

    @Test
    fun `a well-formed image parses`() {
        val img = EvenOtaImage.parse(TestImages.build())
        assertEquals(6, img.components.size)
        assertEquals(EvenOtaImage.MAIN_APP, img.mainApp.name)
        assertEquals(TestImages.DEFAULT_SIZES.sum().toLong(), img.payloadBytes)
        val main = img.mainApp
        assertEquals(5, main.blockCount) // 20000 = 4 × 4096 + 3616
        assertEquals(3616, img.block(main, 4).size)
        assertEquals(128, img.subheader(main).size)
        assertEquals("ota/part0.bin", img.components[0].name)
    }

    @Test
    fun `the image keeps a private copy`() {
        val bytes = TestImages.build()
        val img = EvenOtaImage.parse(bytes)
        val before = img.recomputeSha256()
        bytes.fill(0)
        assertEquals(before, img.recomputeSha256())
    }

    @Test
    fun `five components are accepted, four and seven are not`() {
        EvenOtaImage.parse(TestImages.build(sizes = listOf(100, 200, 300, 400, 5000)))
        rejects("expected") { TestImages.build(sizes = listOf(100, 200, 300, 5000)) }
        rejects("expected") { TestImages.build(sizes = listOf(100, 200, 300, 400, 500, 600, 5000)) }
    }

    @Test
    fun `structural damage is rejected`() {
        rejects("too small") { ByteArray(10) }
        rejects("not an EVENOTA") { TestImages.build { b, _ -> b[0] = 'X'.code.toByte() } }
        rejects("component count") { TestImages.build { b, _ -> TestImages.putU32(b, 8, 0) } }
        rejects("component count") { TestImages.build { b, _ -> TestImages.putU32(b, 8, 1000) } }
        rejects("subheader outside") { TestImages.build { b, l -> TestImages.putU32(b, l.toc(2) + 4, 0x10) } }
        rejects("payload outside") { TestImages.build { b, l -> TestImages.putU32(b, l.subheaderOffsets[5] + 8, 1_000_000) } }
        rejects("table size") { TestImages.build { b, l -> TestImages.putU32(b, l.toc(1) + 8, 1) } }
        rejects("overlap") {
            TestImages.build { b, l ->
                // point component 1 at component 0 (same sizes so only the overlap check trips)
                TestImages.putU32(b, l.toc(1) + 4, l.subheaderOffsets[0].toLong())
                TestImages.putU32(b, l.toc(1) + 8, (l.sizes[0] + 128).toLong())
            }
        }
    }

    @Test
    fun `stale checksums are rejected`() {
        rejects("checksum") { TestImages.build { b, l -> b[l.payload(3)] = (b[l.payload(3)] + 1).toByte() } }
        rejects("checksum") { TestImages.build { b, l -> TestImages.putU32(b, l.toc(0) + 12, 1) } }
        rejects("checksum") { TestImages.build { b, l -> TestImages.putU32(b, l.subheaderOffsets[0] + 12, 1) } }
    }

    @Test
    fun `main application guards`() {
        rejects("missing") { TestImages.build(names = List(6) { "ota/p$it.bin" }) }
        rejects("twice") { TestImages.build(names = List(6) { EvenOtaImage.MAIN_APP }) }
        rejects("load address") {
            TestImages.build { b, l ->
                TestImages.putU32(b, l.payload(5) + 0x14, 0x400000)
                refreshPreamble(b, l)
            }
        }
        rejects("preamble length") {
            TestImages.build { b, l ->
                TestImages.putU32(b, l.payload(5), 0x04000000L or 19_999)
                refreshPreamble(b, l)
            }
        }
        rejects("preamble checksum") {
            TestImages.build { b, l ->
                b[l.payload(5) + 100] = (b[l.payload(5) + 100] + 1).toByte()
                TestImages.fixChecksums(b, l) // component CRC fixed, preamble CRC left stale
            }
        }
    }

    @Test
    fun `the MRAM ceiling is enforced to the byte`() {
        val limit = (EvenOtaImage.APP_MAX_END - EvenOtaImage.APP_LOAD_ADDRESS + EvenOtaImage.APP_PREAMBLE).toInt()
        EvenOtaImage.parse(TestImages.build(sizes = listOf(100, 100, 100, 100, 100, limit)))
        rejects("too large") { TestImages.build(sizes = listOf(100, 100, 100, 100, 100, limit + 1)) }
    }

    private fun refreshPreamble(b: ByteArray, l: TestImages.Layout) {
        val p = l.payload(5)
        TestImages.putU32(b, p + 4, ZlibCrc32.compute(b, p + 8, l.sizes[5] - 8))
        TestImages.fixChecksums(b, l)
    }

    private fun rejects(fragment: String, bytes: () -> ByteArray) {
        val e = assertThrows<InvalidFirmwareException> { EvenOtaImage.parse(bytes()) }
        assertContains(e.message ?: "", fragment)
    }
}
