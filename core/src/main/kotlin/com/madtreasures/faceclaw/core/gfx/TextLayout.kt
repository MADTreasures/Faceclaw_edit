package com.madtreasures.faceclaw.core.gfx

/** Text measurement and line breaking for [BitmapFont]s. All widths are in whole pixels. */
object TextLayout {
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
