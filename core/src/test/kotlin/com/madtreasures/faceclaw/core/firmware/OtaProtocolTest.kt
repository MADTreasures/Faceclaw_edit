package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Crc16
import com.madtreasures.faceclaw.core.protocol.Envelope
import com.madtreasures.faceclaw.core.protocol.G2Messages
import com.madtreasures.faceclaw.core.protocol.hexBytes
import com.madtreasures.faceclaw.core.protocol.toHex
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Byte vectors from docs/analysis/06-firmware.md, Appendix A. */
class OtaProtocolTest {
    @Test
    fun `control messages`() {
        assertEquals("aa210103010 1c00000f0e1".replace(" ", ""), OtaProtocol.begin(1).single().toHex())
        assertEquals("aa21ee03010 1c00003 93d1".replace(" ", ""), OtaProtocol.end(0xEE).single().toHex())
        val sub = ByteArray(128) { (it * 7).toByte() }
        val fc = OtaProtocol.fileCheck(sub, 2).single()
        assertEquals(139, fc.size)
        assertEquals("aa210283010 1c00001".replace(" ", ""), fc.copyOfRange(0, 9).toHex())
        val crc = Crc16.compute(byteArrayOf(1) + sub)
        assertEquals(crc and 0xFF, fc[137].toInt() and 0xFF)
        assertEquals(crc ushr 8, fc[138].toInt() and 0xFF)
    }

    @Test
    fun `a full block is a marker plus 18 data frames sharing one sequence number`() {
        val data = ByteArray(4096) { it.toByte() }
        val frames = OtaProtocol.block(data, 0x42)
        assertEquals(19, frames.size)
        assertEquals("aa214203010 1c00002b2c1".replace(" ", ""), frames[0].toHex())
        for (i in 1..17) {
            assertEquals("aa2142e81 2${"%02x".format(i)}c100".replace(" ", ""), frames[i].copyOfRange(0, 8).toHex())
            assertEquals(240, frames[i].size)
        }
        val last = frames[18]
        assertEquals("aa21429a1212c100", last.copyOfRange(0, 8).toHex())
        assertEquals(8 + 154, last.size)
        val crc = Crc16.compute(data)
        assertEquals(crc and 0xFF, last[last.size - 2].toInt() and 0xFF)
        assertEquals(crc ushr 8, last[last.size - 1].toInt() and 0xFF)
        // the payload stream is the block, unpadded
        val stream = frames.drop(1).flatMap { it.copyOfRange(8, it.size).toList() }.toByteArray()
        assertEquals(data.toList(), stream.copyOf(4096).toList())
    }

    @Test
    fun `a short last block is sent unpadded`() {
        val frames = OtaProtocol.block(ByteArray(2688) { 1 }, 7)
        val body = frames.drop(1).sumOf { it.size - 8 }
        assertEquals(2688 + 2, body)
        assertEquals(12, frames.size - 1)
    }

    @Test
    fun `acknowledgements`() {
        val ack = hexBytes("aa12050401 01c100 0200") + byteArrayOf(0, 0)
        assertEquals(OtaAck(2, 0), OtaProtocol.parseAck(ack))
        assertEquals(OtaAck(3, 8), OtaProtocol.parseAck(hexBytes("aa12070401 01c000 0308 0000")))
        assertNull(OtaProtocol.parseAck(hexBytes("aa21070401 01c000 0308 0000")))
        assertNull(OtaProtocol.parseAck(hexBytes("aa120701")))
        assertEquals("UPDATING", OtaProtocol.statusName(8))
        assertEquals("CHECK_FAIL", OtaProtocol.statusName(7))
        assertEquals("0x2a", OtaProtocol.statusName(42))
    }

    @Test
    fun `stock messages used by the firmware flows`() {
        val auth = Envelope.frame(G2Messages.authRequest(0x60), 0x80, 0, 1).single()
        assertEquals("aa21010c01018000 0804106 01a0408011004 b75d".replace(" ", ""), auth.toHex())
        assertEquals("080c1065720208 00".replace(" ", ""), G2Messages.heartbeat(0x65).toHex())
        assertEquals("08091066 5a020800".replace(" ", ""), G2Messages.shutdownPage(0x66).toHex())
        val page = PromptMessages.createPage(0x70, "Hi", listOf("No", "Yes"))
        val expected = "0800 1070 1a4c 0802" +
            " 1228 0800 109601 189802 2078 4802 5209666c6173686d656e75 5a0d 0802 1801 22024e6f 2203596573 6001" +
            " 1a1b 0800 1000 189802 208201 4801 5209666c6173687761726e 62024869" +
            " 28904e"
        assertEquals(expected.replace(" ", ""), page.toHex(), "prompt page")
    }
}
