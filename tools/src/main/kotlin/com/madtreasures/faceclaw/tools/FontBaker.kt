package com.madtreasures.faceclaw.tools

import com.madtreasures.faceclaw.core.gfx.BitmapFont
import com.madtreasures.faceclaw.core.gfx.Glyph
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Rasterises TTF fonts into the `.fcf` bitmap format used at runtime.
 *
 * The set of baked fonts and sizes is the typographic palette available to the UI theme;
 * add sizes here (and run `./gradlew :tools:bakeFonts`) when a design needs them.
 */
class FontBaker(private val srcDir: File, private val outDir: File, private val iconsKt: File) {

    private data class Spec(
        val name: String,
        val file: String,
        val size: Int,
        val chars: IntArray,
        val gamma: Double = 1.0,
        val flags: Int = 0,
        val kerning: Boolean = true,
    )

    private val text: IntArray = buildSet {
        addAll(0x20..0x7E)
        addAll(0xA0..0xFF)
        addAll(0x100..0x17F) // Latin Extended-A
        addAll(listOf(0x192, 0x218, 0x219, 0x21A, 0x21B)) // ƒ Ș ș Ț ț
        addAll(0x391..0x3A9) // Greek capitals
        addAll(0x3B1..0x3C9) // Greek small
        addAll(0x401..0x45F) // Cyrillic
        addAll(0x2010..0x2027) // dashes, quotes, bullet, ellipsis
        addAll(listOf(0x2030, 0x2032, 0x2033, 0x2039, 0x203A, 0x2044, 0x20AC, 0x20BF, 0x2116, 0x2122))
        addAll(0x2190..0x2199) // arrows
        addAll(listOf(0x21A9, 0x21AA, 0x21B5, 0x2212, 0x2215, 0x2219, 0x221A, 0x221E, 0x2248, 0x2260, 0x2264, 0x2265))
        addAll(listOf(0x2303, 0x2318, 0x2325, 0x232B, 0x25A0, 0x25A1, 0x25B2, 0x25B6, 0x25BC, 0x25C0, 0x25CB, 0x25CF))
        addAll(listOf(0x2600, 0x2601, 0x2605, 0x2606, 0x2713, 0x2715, 0x2717, 0xFFFD))
    }.sorted().toIntArray()

    /** Big display sizes only need what a clock, timer or big number shows. */
    private val digits: IntArray = "0123456789:.,-+%°/ APMapm·–—h".codePoints().toArray().distinct().sorted().toIntArray()

    private fun specs(iconChars: IntArray): List<Spec> = buildList {
        for (s in listOf(14, 16, 18, 20, 22, 24, 28, 32)) add(Spec("inter-regular-$s", "inter/Inter-Regular.ttf", s, text))
        for (s in listOf(14, 16, 18, 20, 22, 24, 28)) add(Spec("inter-medium-$s", "inter/Inter-Medium.ttf", s, text))
        for (s in listOf(16, 18, 20, 22, 24, 28, 32, 40)) add(Spec("inter-semibold-$s", "inter/Inter-SemiBold.ttf", s, text))
        for (s in listOf(24, 32, 40, 48)) add(Spec("inter-bold-$s", "inter/Inter-Bold.ttf", s, text))
        for (s in listOf(48, 64, 80, 96, 128)) add(Spec("inter-display-light-$s", "inter/InterDisplay-Light.ttf", s, digits))
        for (s in listOf(48, 64, 80)) add(Spec("inter-display-regular-$s", "inter/InterDisplay-Regular.ttf", s, digits))
        for (s in listOf(14, 16, 18, 20, 24)) add(Spec("mono-regular-$s", "jetbrains-mono/JetBrainsMono-Regular.ttf", s, text, flags = BitmapFont.FLAG_MONOSPACE, kerning = false))
        for (s in listOf(16, 20)) add(Spec("mono-bold-$s", "jetbrains-mono/JetBrainsMono-Bold.ttf", s, text, flags = BitmapFont.FLAG_MONOSPACE, kerning = false))
        for (s in listOf(16, 20, 24, 28, 32, 40, 48, 64)) add(Spec("icons-$s", "material-icons/MaterialIcons-Regular.ttf", s, iconChars, flags = BitmapFont.FLAG_ICONS, kerning = false))
    }

    fun run() {
        outDir.mkdirs()
        val icons = IconCatalog.load(File(srcDir, "material-icons/MaterialIcons-Regular.codepoints"))
        val iconChars = icons.values.distinct().sorted().toIntArray()
        IconCatalog.writeKotlin(icons, iconsKt)
        println("wrote ${icons.size} icon constants to $iconsKt")
        var total = 0L
        for (spec in specs(iconChars)) {
            val font = bake(spec)
            val out = File(outDir, "${spec.name}.fcf")
            out.outputStream().use { font.write(it) }
            total += out.length()
            println("baked ${spec.name}: ${font.glyphs.size} glyphs, ${font.kerningPairs.size} kern pairs, ${out.length()} bytes")
        }
        println("total ${total / 1024} KiB")
    }

    private fun bake(spec: Spec): BitmapFont {
        val base = File(srcDir, spec.file).inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
        val font = base.deriveFont(spec.size.toFloat())
        val frc = FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        val lm = font.getLineMetrics("Hgjy", frc)
        val isIcons = spec.flags and BitmapFont.FLAG_ICONS != 0
        val ascent = if (isIcons) spec.size else ceil(lm.ascent.toDouble()).toInt()
        val descent = if (isIcons) 0 else ceil(lm.descent.toDouble()).toInt()
        val lineHeight = if (isIcons) spec.size else ceil((lm.ascent + lm.descent + lm.leading).toDouble()).toInt()

        val glyphs = ArrayList<Glyph>()
        for (cp in spec.chars) {
            if (!font.canDisplay(cp)) continue
            val s = String(Character.toChars(cp))
            val gv = font.createGlyphVector(frc, s)
            if (gv.getGlyphCode(0) == font.missingGlyphCode) continue
            val adv = gv.getGlyphMetrics(0).advanceX
            val pb = gv.getPixelBounds(frc, 0f, 0f)
            if (pb.width <= 0 || pb.height <= 0) {
                glyphs += Glyph(cp, (adv * 16).roundToInt(), 0, 0, 0, 0, ByteArray(0))
                continue
            }
            val img = BufferedImage(pb.width, pb.height, BufferedImage.TYPE_BYTE_GRAY)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.drawGlyphVector(gv, -pb.x.toFloat(), -pb.y.toFloat())
            g.dispose()
            val raster = img.raster
            val alpha = ByteArray(pb.width * pb.height)
            for (y in 0 until pb.height) for (x in 0 until pb.width) {
                var v = raster.getSample(x, y, 0)
                if (spec.gamma != 1.0 && v in 1..254) v = (255 * (v / 255.0).pow(spec.gamma)).roundToInt()
                alpha[y * pb.width + x] = v.toByte()
            }
            // Icons are positioned by their em box: baseline at the bottom of the square.
            glyphs += Glyph(cp, (adv * 16).roundToInt(), pb.x, pb.y, pb.width, pb.height, alpha)
        }

        fun boundsTop(ch: Char): Int {
            val gv = font.createGlyphVector(frc, ch.toString())
            return -gv.getPixelBounds(frc, 0f, 0f).y
        }
        val capHeight = if (isIcons) spec.size else boundsTop('H')
        val xHeight = if (isIcons) spec.size else boundsTop('x')

        val kerning = HashMap<Long, Int>()
        if (spec.kerning) {
            val kernFont = font.deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))
            val kernChars = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
                ".,:;'\"-!?()/ÄÖÜäöüÀÁÈÉàáèéçñ").toCharArray().filter { font.canDisplay(it) && it.code in spec.chars }
            val advances = kernChars.associateWith { c -> font.createGlyphVector(frc, c.toString()).getGlyphMetrics(0).advanceX }
            for (a in kernChars) for (b in kernChars) {
                val arr = charArrayOf(a, b)
                val gv = kernFont.layoutGlyphVector(frc, arr, 0, 2, Font.LAYOUT_LEFT_TO_RIGHT)
                val kern = gv.getGlyphPosition(1).x - advances.getValue(a)
                val q4 = (kern * 16).roundToInt()
                if (q4 != 0) kerning[BitmapFont.pairKey(a.code, b.code)] = q4
            }
        }
        val family = base.getFamily(java.util.Locale.ROOT)
        val style = base.getFontName(java.util.Locale.ROOT)
        return BitmapFont(spec.name, family, style, spec.size, ascent, descent, lineHeight, capHeight, xHeight, spec.flags, glyphs, kerning)
    }
}
