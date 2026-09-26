package com.madtreasures.faceclaw.core.gfx

/** Text measurement and line breaking for [BitmapFont]s. All widths are in whole pixels. */
object TextLayout {
    private val emoticons = mapOf(
        0x1F600 to ":D", 0x1F601 to ":D", 0x1F602 to ":'D", 0x1F603 to ":D", 0x1F604 to ":D", 0x1F605 to ":D",
        0x1F609 to ";)", 0x1F60A to ":)", 0x1F60D to "<3", 0x1F618 to ":*", 0x1F61B to ":P", 0x1F622 to ":'(",
        0x1F62D to ":'(", 0x1F620 to ">:(", 0x1F621 to ">:(", 0x1F642 to ":)", 0x1F641 to ":(", 0x1F610 to ":|",
        0x1F914 to "(?)", 0x1F923 to "xD", 0x1F44D to "(+1)", 0x1F44E to "(-1)", 0x1F44C to "(ok)", 0x1F64F to "(thanks)",
        0x2764 to "<3", 0x1F496 to "<3", 0x1F525 to "(fire)", 0x1F389 to "(party)", 0x1F4F7 to "(photo)", 0x1F44B to "(wave)",
        0x2705 to "\u2713", 0x274C to "\u2715",
    )

    /**
     * Prepares arbitrary text (e.g. from phone notifications) for the baked fonts: common emoji
     * become emoticons, variation selectors and joiners disappear.
     */
    fun sanitize(text: String): String {
        if (text.none { it.isSurrogate() || it.code in 0x2600..0x27BF || it.code == 0xFE0F || it.code == 0x200D }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0xFE0F || cp == 0x200D || cp in 0x1F3FB..0x1F3FF -> {}
                emoticons.containsKey(cp) -> sb.append(emoticons.getValue(cp))
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    const val ELLIPSIS = "…"

    /** Width of [text] in 1/16 px, including kerning. */
    fun widthQ4(font: BitmapFont, text: CharSequence): Int {
        var w = 0
        var prev = -1
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) continue
            if (prev >= 0) w += font.kernQ4(prev, cp)
            w += font.glyphOrFallback(cp)?.advanceQ4 ?: 0
            prev = cp
        }
        return w
    }

    fun width(font: BitmapFont, text: CharSequence): Int = (widthQ4(font, text) + 15) / 16

    /** Truncates [text] with an ellipsis so it fits in [maxWidth] pixels. */
    fun ellipsize(font: BitmapFont, text: String, maxWidth: Int): String {
        if (width(font, text) <= maxWidth) return text
        val ell = widthQ4(font, ELLIPSIS)
        val limit = maxWidth * 16 - ell
        if (limit <= 0) return ""
        var w = 0
        var prev = -1
        var i = 0
        var lastFit = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (prev >= 0) w += font.kernQ4(prev, cp)
            w += font.glyphOrFallback(cp)?.advanceQ4 ?: 0
            if (w > limit) break
            lastFit = next
            prev = cp
            i = next
        }
        return text.substring(0, lastFit).trimEnd() + ELLIPSIS
    }

    /**
     * Greedy word wrap. Honours explicit newlines, breaks after spaces and hyphens, and
     * splits words that are wider than a whole line.
     */
    fun wrap(font: BitmapFont, text: String, maxWidth: Int): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            wrapParagraph(font, paragraph, maxWidth, lines)
        }
        return lines
    }

    private fun wrapParagraph(font: BitmapFont, paragraph: String, maxWidth: Int, out: MutableList<String>) {
        if (paragraph.isEmpty()) {
            out += ""
            return
        }
        val limit = maxWidth * 16
        var lineStart = 0
        var i = 0
        var lastBreak = -1 // index just after a break opportunity
        var w = 0
        var prev = -1
        while (i < paragraph.length) {
            val cp = paragraph.codePointAt(i)
            val next = i + Character.charCount(cp)
            var adv = font.glyphOrFallback(cp)?.advanceQ4 ?: 0
            if (prev >= 0) adv += font.kernQ4(prev, cp)
            if (w + adv > limit && cp != ' '.code && i > lineStart) {
                val breakAt = if (lastBreak > lineStart) lastBreak else i
                out += paragraph.substring(lineStart, breakAt).trimEnd()
                lineStart = breakAt
                while (lineStart < paragraph.length && paragraph[lineStart] == ' ') lineStart++
                i = lineStart
                w = 0
                prev = -1
                lastBreak = -1
                continue
            }
            w += adv
            prev = cp
            if (cp == ' '.code || cp == '-'.code || cp == '/'.code || cp == 0x2013 || cp == 0x2014) lastBreak = next
            i = next
        }
        if (lineStart < paragraph.length) out += paragraph.substring(lineStart).trimEnd()
    }
}
