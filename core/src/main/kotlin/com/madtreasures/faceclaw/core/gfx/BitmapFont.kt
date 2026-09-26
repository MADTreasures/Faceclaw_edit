package com.madtreasures.faceclaw.core.gfx

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One pre-rasterised glyph. [alpha] holds [width]*[height] coverage bytes (0..255).
 * [bearingX]/[bearingY] place the bitmap relative to the pen position on the baseline;
 * [bearingY] is negative for pixels above the baseline.
 */
class Glyph(
    val codepoint: Int,
    /** Advance in 1/16 px. */
    val advanceQ4: Int,
    val bearingX: Int,
    val bearingY: Int,
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
)

/**
 * A font baked to a fixed pixel size. Fonts are produced by the `tools` module from TTF
 * sources and loaded at runtime from `/fonts/<name>.fcf` on the classpath, so text renders
 * identically on the phone, in the simulator and in tests.
 */
class BitmapFont(
    val name: String,
    val family: String,
    val style: String,
    val pixelSize: Int,
    /** Pixels above the baseline. */
    val ascent: Int,
    /** Pixels below the baseline. */
    val descent: Int,
    val lineHeight: Int,
    val capHeight: Int,
    val xHeight: Int,
    val flags: Int,
    glyphs: Collection<Glyph>,
    kerning: Map<Long, Int>,
) {
    private val glyphMap: HashMap<Int, Glyph> = HashMap<Int, Glyph>(glyphs.size * 2).also { m -> glyphs.forEach { m[it.codepoint] = it } }
    private val kernMap: HashMap<Long, Int> = HashMap(kerning)

    val glyphs: Collection<Glyph> get() = glyphMap.values
    val kerningPairs: Map<Long, Int> get() = kernMap
    val isMonospace: Boolean get() = flags and FLAG_MONOSPACE != 0

    fun hasGlyph(codepoint: Int): Boolean = glyphMap.containsKey(codepoint)

    fun glyph(codepoint: Int): Glyph? = glyphMap[codepoint]

    /** Returns the glyph for [codepoint], falling back to a base letter, '?' or a blank advance. */
    fun glyphOrFallback(codepoint: Int): Glyph? {
        glyphMap[codepoint]?.let { return it }
        if (codepoint == 0x00A0 || codepoint == 0x2007 || codepoint == 0x202F) return glyphMap[0x20]
        TextFallbacks.baseLetter(codepoint)?.let { base -> glyphMap[base]?.let { return it } }
        return glyphMap[FALLBACK] ?: glyphMap['?'.code]
    }

    fun kernQ4(left: Int, right: Int): Int = if (kernMap.isEmpty()) 0 else kernMap[pairKey(left, right)] ?: 0

    fun write(out: OutputStream) {
        val d = DataOutputStream(GZIPOutputStream(out))
        d.writeBytes(MAGIC)
        d.writeShort(VERSION)
        d.writeUTF(family)
        d.writeUTF(style)
        d.writeShort(pixelSize)
        d.writeShort(ascent)
        d.writeShort(descent)
        d.writeShort(lineHeight)
        d.writeShort(capHeight)
        d.writeShort(xHeight)
        d.writeByte(flags)
        val sorted = glyphMap.values.sortedBy { it.codepoint }
        d.writeInt(sorted.size)
        for (g in sorted) {
            d.writeInt(g.codepoint)
            d.writeShort(g.advanceQ4)
            d.writeShort(g.bearingX)
            d.writeShort(g.bearingY)
            d.writeShort(g.width)
            d.writeShort(g.height)
            d.write(g.alpha)
        }
        val kerns = kernMap.entries.sortedBy { it.key }
        d.writeInt(kerns.size)
        for ((key, value) in kerns) {
            d.writeInt((key ushr 32).toInt())
            d.writeInt(key.toInt())
            d.writeShort(value)
        }
        d.close()
    }

    companion object {
        const val FLAG_MONOSPACE = 1
        const val FLAG_ICONS = 2
        /** WHITE SQUARE, used as the "missing glyph" box when the font has it. */
        const val FALLBACK = 0x25A1
        private const val MAGIC = "FCF1"
        private const val VERSION = 1

        fun pairKey(left: Int, right: Int): Long = (left.toLong() shl 32) or (right.toLong() and 0xFFFFFFFFL)

        fun read(name: String, input: InputStream): BitmapFont {
            val d = DataInputStream(GZIPInputStream(input.buffered()))
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "not a baked font: $name" }
            val version = d.readUnsignedShort()
            require(version == VERSION) { "unsupported font version $version in $name" }
            val family = d.readUTF()
            val style = d.readUTF()
            val pixelSize = d.readUnsignedShort()
            val ascent = d.readShort().toInt()
            val descent = d.readShort().toInt()
            val lineHeight = d.readShort().toInt()
            val capHeight = d.readShort().toInt()
            val xHeight = d.readShort().toInt()
            val flags = d.readUnsignedByte()
            val count = d.readInt()
            val glyphs = ArrayList<Glyph>(count)
            repeat(count) {
                val cp = d.readInt()
                val adv = d.readShort().toInt()
                val bx = d.readShort().toInt()
                val by = d.readShort().toInt()
                val w = d.readUnsignedShort()
                val h = d.readUnsignedShort()
                val alpha = ByteArray(w * h).also { d.readFully(it) }
                glyphs += Glyph(cp, adv, bx, by, w, h, alpha)
            }
            val kernCount = d.readInt()
            val kerning = HashMap<Long, Int>(kernCount * 2)
            repeat(kernCount) {
                val l = d.readInt()
                val r = d.readInt()
                kerning[pairKey(l, r)] = d.readShort().toInt()
            }
            return BitmapFont(name, family, style, pixelSize, ascent, descent, lineHeight, capHeight, xHeight, flags, glyphs, kerning)
        }
    }
}

/** Loads and caches baked fonts from the classpath (`/fonts/<name>.fcf`). */
object FontLibrary {
    private val cache = HashMap<String, BitmapFont>()

    @Synchronized
    fun get(name: String): BitmapFont = cache.getOrPut(name) {
        val stream = FontLibrary::class.java.getResourceAsStream("/fonts/$name.fcf")
            ?: error("font resource /fonts/$name.fcf not found")
        stream.use { BitmapFont.read(name, it) }
    }

    fun exists(name: String): Boolean = FontLibrary::class.java.getResource("/fonts/$name.fcf") != null
}

internal object TextFallbacks {
    /** Maps accented Latin letters missing from a font to their unaccented base letter. */
    fun baseLetter(cp: Int): Int? {
        val s = String(Character.toChars(cp))
        val normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        if (normalized.isEmpty()) return null
        val base = normalized.codePointAt(0)
        return if (base != cp && base < 0x2000) base else null
    }
}
