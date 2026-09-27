package com.madtreasures.faceclaw.core.firmware

import java.util.Random

/** Builds synthetic but fully valid EVENOTA images (never Even's firmware). */
object TestImages {
    val DEFAULT_SIZES = listOf(3000, 5000, 4096, 100, 9000, 20_000)

    /** Offsets of the parts of a built image, for tests that corrupt them. */
    class Layout(val subheaderOffsets: List<Int>, val sizes: List<Int>) {
        fun toc(i: Int) = EvenOtaImage.HEADER_SIZE + i * EvenOtaImage.TOC_ENTRY_SIZE
        fun payload(i: Int) = subheaderOffsets[i] + EvenOtaImage.SUBHEADER_SIZE
    }

    /**
     * An image with components of [sizes]; the last one is the main application with a correct
     * preamble. [names] defaults to "ota/partN.bin" plus the main app.
     */
    fun build(
        sizes: List<Int> = DEFAULT_SIZES,
        seed: Long = 1,
        names: List<String> = sizes.indices.map { if (it == sizes.lastIndex) EvenOtaImage.MAIN_APP else "ota/part$it.bin" },
        tweak: (ByteArray, Layout) -> Unit = { _, _ -> },
    ): ByteArray {
        val rnd = Random(seed)
        val n = sizes.size
        val tocEnd = EvenOtaImage.HEADER_SIZE + n * EvenOtaImage.TOC_ENTRY_SIZE
        val offsets = ArrayList<Int>()
        var pos = tocEnd
        for (s in sizes) {
            offsets += pos
            pos += EvenOtaImage.SUBHEADER_SIZE + s
        }
        val img = ByteArray(pos)
        EvenOtaImage.MAGIC.copyInto(img, 0)
        putU32(img, 8, n.toLong())
        val layout = Layout(offsets, sizes)
        for (i in 0 until n) {
            val off = offsets[i]
            val ps = sizes[i]
            // subheader: unknown fields get random bytes, then size, CRC slot and name
            for (k in 0 until EvenOtaImage.SUBHEADER_SIZE) img[off + k] = rnd.nextInt(256).toByte()
            putU32(img, off + 8, ps.toLong())
            for (k in 0 until EvenOtaImage.NAME_LENGTH) img[off + EvenOtaImage.NAME_OFFSET + k] = 0
            names[i].toByteArray(Charsets.ISO_8859_1).copyInto(img, off + EvenOtaImage.NAME_OFFSET)
            val p = layout.payload(i)
            for (k in 0 until ps) img[p + k] = rnd.nextInt(256).toByte()
            if (names[i] == EvenOtaImage.MAIN_APP && ps >= EvenOtaImage.APP_PREAMBLE) {
                putU32(img, p, (ps.toLong() and 0xFFFFFF) or 0x04000000L)
                putU32(img, p + 0x14, EvenOtaImage.APP_LOAD_ADDRESS)
                putU32(img, p + 4, ZlibCrc32.compute(img, p + 8, ps - 8))
            }
            val toc = layout.toc(i)
            putU32(img, toc, i.toLong())
            putU32(img, toc + 4, off.toLong())
            putU32(img, toc + 8, (ps + EvenOtaImage.SUBHEADER_SIZE).toLong())
        }
        fixChecksums(img, layout)
        tweak(img, layout)
        return img
    }

    fun fixChecksums(img: ByteArray, layout: Layout) {
        for (i in layout.sizes.indices) {
            val crc = Crc32cMsb.compute(img, layout.payload(i), layout.sizes[i])
            putU32(img, layout.toc(i) + 12, crc)
            putU32(img, layout.subheaderOffsets[i] + 12, crc)
        }
    }

    fun putU32(b: ByteArray, off: Int, v: Long) {
        for (k in 0 until 4) b[off + k] = (v ushr (8 * k)).toByte()
    }
}
