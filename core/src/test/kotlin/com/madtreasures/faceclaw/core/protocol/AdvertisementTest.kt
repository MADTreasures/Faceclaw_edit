package com.madtreasures.faceclaw.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvertisementTest {
    // docs/analysis/01-ble-link.md §3.2 test vector (company id stripped as Android delivers it)
    private val mfg = "S211GBBC180304".toByteArray() + hexBytes("E0ECB61412E0") + byteArrayOf(1)

    @Test
    fun parsesLeftTemple() {
        val a = G2Advertisement.parse("e0:12:14:b6:ec:e0", "Even G2_32_L_B6ECE0", mfg, -60)!!
        assertEquals(Arm.Left, a.side)
        assertEquals("S211GBBC180304", a.serial)
        assertEquals("E0:12:14:B6:EC:E0", a.address)
    }

    @Test
    fun rejectsUnplaceableNames() {
        assertNull(G2Advertisement.parse("AA:BB:CC:DD:EE:FF", "Even G2_32_X_B6ECE0", mfg, -60))
        assertNull(G2Advertisement.parse("AA:BB:CC:DD:EE:FF", "Some headphones", null, -60))
    }

    @Test
    fun pairsTemplesBySerial() {
        val l = G2Advertisement.parse("E0:12:14:B6:EC:E0", "Even G2_32_L_B6ECE0", mfg, -60)!!
        val r = G2Advertisement.parse("E0:12:14:B6:EC:E1", "Even G2_32_R_B6ECE1", mfg, -55)!!
        val other = G2Advertisement.parse("11:22:33:44:55:66", "Even G2_32_R_445566", "S211GBBC999999".toByteArray() + ByteArray(7), -70)!!
        val pairs = G2Advertisement.pairUp(listOf(l, r, other))
        assertEquals(2, pairs.size)
        assertTrue(pairs[0].complete)
        assertEquals(l, pairs[0].left)
        assertEquals(r, pairs[0].right)
        assertTrue(!pairs[1].complete)
    }
}
