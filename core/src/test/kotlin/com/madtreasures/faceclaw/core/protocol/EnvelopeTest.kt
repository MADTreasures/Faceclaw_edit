package com.madtreasures.faceclaw.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Byte vectors from docs/analysis/01-ble-link.md §4 (verified against the reference implementations). */
class EnvelopeTest {
    private fun single(pb: ByteArray, sid: Int, flag: Int, seq: Int): String {
        val frames = Envelope.frame(pb, sid, flag, seq)
        assertEquals(1, frames.size)
        return frames[0].toHex()
    }

    @Test
    fun crcCheckValue() {
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray()))
        assertEquals(0xFFFF, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun prelude() {
        assertEquals(
            "aa219213010101200802109c01220a1a0812061204080010 00a142".replace(" ", ""),
            single(G2Messages.prelude(), Sid.DASHBOARD, Envelope.FLAG_REQUEST, 0x92),
        )
    }

    @Test
    fun heartbeat() {
        assertEquals("aa21400b0101e020080c10960172020800 81b6".replace(" ", ""),
            single(G2Messages.heartbeat(150), Sid.EVENHUB, Envelope.FLAG_REQUEST, 0x40))
    }

    @Test
    fun settingsRead() {
        assertEquals("aa21410a01010920080210652202080 1c642".replace(" ", ""),
            single(G2Messages.settingsRead(101), Sid.SETTINGS, Envelope.FLAG_REQUEST, 0x41))
    }

    @Test
    fun authRequest() {
        assertEquals("aa21420c01018000080410641a0408011004719c",
            single(G2Messages.authRequest(100), Sid.DEV_CONFIG, Envelope.FLAG_NONE, 0x42))
    }

    @Test
    fun framebufferLease() {
        assertEquals("aa21430f0101092008011000aa0606464301050000 2926".replace(" ", ""),
            single(G2Messages.cfwControl(G2Messages.CfwOp.FB_ACQUIRE), Sid.SETTINGS, Envelope.FLAG_REQUEST, 0x43))
    }

    @Test
    fun shutdown() {
        assertEquals("aa21450a0101e0200809106 65a020800ac30".replace(" ", ""),
            single(G2Messages.shutdownPage(102), Sid.EVENHUB, Envelope.FLAG_REQUEST, 0x45))
    }

    @Test
    fun createInputPage() {
        assertEquals(
            ("aa21442b0101e020" + "080010651a2308011a1c0800100018c00420a00248015209" +
                "64617368626f617264" + "5801620120" + "28904e" + "2f48"),
            single(G2Messages.createInputPage(101), Sid.EVENHUB, Envelope.FLAG_REQUEST, 0x44),
        )
    }

    @Test
    fun multiFragment() {
        val pb = ByteArray(300) { it.toByte() }
        val frames = Envelope.frame(pb, 0xE0, 0x20, 0x46)
        assertEquals(2, frames.size)
        assertEquals("aa2146e80201e020", frames[0].copyOfRange(0, 8).toHex())
        assertEquals("aa2146460202e020", frames[1].copyOfRange(0, 8).toHex())
        assertEquals(8 + 70, frames[1].size)
    }

    @Test
    fun reassemblesSplitAndConcatenatedFrames() {
        val got = ArrayList<InboundMessage>()
        val r = FrameReassembler({ got += it }, {})
        val pb = ByteArray(500) { (it * 7).toByte() }
        // pb must be valid protobuf for the reassembler, so wrap the bytes in a field
        val msg = ProtoWriter.build { uint(1, 2); uint(2, 77); bytes(3, pb) }
        val frames = Envelope.frame(msg, 0xE0, 0x01, 9).map { f -> f.also { it[1] = Envelope.RX.toByte() } }
        val all = frames.reduce { a, b -> a + b }
        // feed in odd-sized pieces
        var i = 0
        while (i < all.size) {
            val n = minOf(37, all.size - i)
            r.push(all.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(1, got.size)
        assertEquals(77, got[0].magic)
        assertContentEquals(msg, got[0].payload)
        assertTrue(got[0].isNotify)
    }
}
