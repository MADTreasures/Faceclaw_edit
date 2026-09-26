package com.madtreasures.faceclaw.core.protocol

import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Vectors from docs/analysis/01-ble-link.md §7 and 02-display.md §3/§5. */
class CfwTest {
    @Test
    fun goldenUncompressedPacketization() {
        val packets = CfwEncoder(compress = false).encode("123456789abcdef".toByteArray(), streamId = 255, lensMask = 3, mtu = 23)
        assertEquals(
            listOf(
                "aa21ff0c0101f000830b0f0078c331323334862b",
                "aa21000c0101f00003353637383961626364" + "8c7b",
                "aa2101050101f000436566de70",
            ),
            packets.map { it.toHex() },
        )
    }

    @Test
    fun clearAndPresentExample() {
        val enc = CfwEncoder(compress = false)
        val clear = CfwDraw.drawMessages(listOf(CfwDraw.clear(0))).single()
        assertEquals("1a0100030009 0000".replace(" ", ""), clear.toHex())
        assertEquals("aa216410 0101f000c30b0800d4551a01000300090000d337".replace(" ", ""), enc.encode(clear, 100, 3, 512).single().toHex())
        assertEquals("aa21650901 01f000c30b01004d321c1cd9".replace(" ", ""), enc.encode(CfwDraw.present(), 101, 3, 512).single().toHex())
    }

    @Test
    fun compressedRecordsUsePersistentContext() {
        val enc = CfwEncoder(compress = true)
        val first = enc.record(byteArrayOf(7, 2), 3)
        assertEquals("0f0a00daa4789c626702000000ffff", first.toHex())
        val second = enc.record(byteArrayOf(7, 2), 3)
        assertEquals(0x07, second[0].toInt())
        // both bodies inflate in sequence with one inflater
        val inf = Inflater()
        val out = ByteArray(16)
        inf.setInput(first.copyOfRange(5, first.size))
        assertEquals(2, inf.inflate(out))
        inf.setInput(second.copyOfRange(5, second.size))
        assertEquals(2, inf.inflate(out))
        assertContentEquals(byteArrayOf(7, 2), out.copyOfRange(0, 2))
    }

    @Test
    fun drawCallVectors() {
        val px8x2 = ByteArray(640 * 2) { if (it % 640 < 8) 15 else 0 }
        assertEquals("010000000002010f10", CfwDraw.bbox(px8x2, 640, 0, 0, 8, 2).toHex())
        val px = ByteArray(640) { if (it == 1) 15 else 0 }
        assertEquals("01000101000000010001001f", CfwDraw.bbox(px, 640, 1, 0, 1, 1).toHex())
        val black = ByteArray(640 * 32)
        assertEquals("0100000000a01000000050", CfwDraw.bbox(black, 640, 0, 0, 640, 32).toHex())
    }

    @Test
    fun ackParsing() {
        val acks = CfwAcks.parse(hexBytes("aa12050b0101f000 01 64 0000 01 0200 daa4 3f39".replace(" ", "")))
        assertNotNull(acks)
        assertEquals(CfwAck(1, 100, 0, 1, 2, 0xa4da), acks.single())
        val nack = CfwAcks.parse(hexBytes("aa12070b0101f000 03 64 0000 02 0000 daa4 e2fc".replace(" ", "")))
        assertEquals(3, nack!!.single().kind)
        val built = CfwAcks.build(1, 100, 2, 2, 0xa4da, listOf(CfwAck(1, 99, 0, 2, 5, 0x1234)))
        val parsed = CfwAcks.parse(built)!!
        assertEquals(2, parsed.size)
        assertEquals(99, parsed[1].streamId)
        assertEquals(2, parsed[1].lens)
    }
}
