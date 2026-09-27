package com.madtreasures.faceclaw.core.firmware

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Checks the whole pipeline against Even's real stock image. Opt-in, because the image is
 * Even's property and never part of this repository: download it yourself and run
 * `FACECLAW_STOCK_IMAGE=/path/to/g2_2.3.0.24.bin ./gradlew :core:test`.
 */
class RealImageTest {
    private val path = System.getenv("FACECLAW_STOCK_IMAGE")

    @Test
    fun `stock image validates and the custom image is reproduced bit for bit`() {
        assumeTrue(path != null && File(path).isFile, "FACECLAW_STOCK_IMAGE not set")
        val stock = File(path!!).readBytes()
        assertEquals(FirmwareCatalog.STOCK_SIZE, stock.size)
        assertEquals(FirmwareCatalog.STOCK_MD5, Digests.md5(stock))

        val stockImage = FirmwareCatalog.prepare(FirmwareKind.Stock, stock)
        assertEquals(FirmwareCatalog.STOCK_SHA256, stockImage.sha256)
        assertEquals(6, stockImage.components.size)
        with(stockImage.mainApp) {
            assertEquals(5, index)
            assertEquals(0xbe36b, offset)
            assertEquals(0x395a80, size)
            assertEquals(0x2a32c6c0L, crc)
            assertEquals(918, blockCount)
        }

        val custom = FirmwareCatalog.prepare(FirmwareKind.Custom, stock)
        assertEquals(FirmwareCatalog.CUSTOM_SHA256, custom.sha256)
        assertEquals(FirmwareCatalog.CUSTOM_SIZE, custom.size)
        with(custom.mainApp) {
            assertEquals(0x3a6ae0, size)
            assertEquals(0x833dd6f8L, crc)
            assertEquals(935, blockCount)
        }
        // components 0-4 are untouched by the patch set
        for (i in 0 until 5) {
            assertEquals(stockImage.payload(stockImage.components[i]).toList(), custom.payload(custom.components[i]).toList())
        }
        for (c in custom.components) println("component ${c.index}: ${c.name} ${c.size} bytes, ${c.blockCount} blocks")
    }
}
