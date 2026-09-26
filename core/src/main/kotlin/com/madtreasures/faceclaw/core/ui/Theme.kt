package com.madtreasures.faceclaw.core.ui

import com.madtreasures.faceclaw.core.gfx.BitmapFont
import com.madtreasures.faceclaw.core.gfx.FontLibrary

/**
 * The design system. Every visual decision (brightness levels, type, spacing, shapes,
 * motion) lives here so the whole look can be changed in one place.
 *
 * The G2 display is additive light on a transparent lens: black is invisible, so the
 * design keeps large areas black and uses brightness (not colour) for hierarchy.
 * Levels are multiples of 17 so they map exactly onto the 16 grey levels of the panel.
 */
data class Theme(
    val levels: Levels = Levels(),
    val type: Typography = Typography(),
    val spacing: Spacing = Spacing(),
    val shapes: Shapes = Shapes(),
    val motion: Motion = Motion(),
) {
    companion object {
        val Default = Theme()
    }
}

data class Levels(
    val background: Int = 0,
    /** Subtle fills: selection capsules, cards. */
    val surface: Int = 34,
    val surfaceStrong: Int = 51,
    /** Hairlines and dividers. */
    val divider: Int = 51,
    /** Outlines of focused elements. */
    val outline: Int = 170,
    val textFaint: Int = 102,
    val textDim: Int = 153,
    val text: Int = 221,
    val textStrong: Int = 255,
    val accent: Int = 255,
)

/** Font roles; names refer to baked fonts in core/src/main/resources/fonts. */
data class Typography(
    val statusName: String = "inter-medium-18",
    val captionName: String = "inter-medium-16",
    val bodyName: String = "inter-regular-22",
    val bodyStrongName: String = "inter-semibold-22",
    val titleName: String = "inter-semibold-28",
    val headlineName: String = "inter-bold-40",
    val displayName: String = "inter-display-light-128",
    val displayMediumName: String = "inter-display-light-80",
    val displaySmallName: String = "inter-display-light-48",
    val monoName: String = "mono-regular-18",
) {
    val status: BitmapFont get() = FontLibrary.get(statusName)
    val caption: BitmapFont get() = FontLibrary.get(captionName)
    val body: BitmapFont get() = FontLibrary.get(bodyName)
    val bodyStrong: BitmapFont get() = FontLibrary.get(bodyStrongName)
    val title: BitmapFont get() = FontLibrary.get(titleName)
    val headline: BitmapFont get() = FontLibrary.get(headlineName)
    val display: BitmapFont get() = FontLibrary.get(displayName)
    val displayMedium: BitmapFont get() = FontLibrary.get(displayMediumName)
    val displaySmall: BitmapFont get() = FontLibrary.get(displaySmallName)
    val mono: BitmapFont get() = FontLibrary.get(monoName)

    /** Icon font of the given pixel size (16, 20, 24, 28, 32, 40, 48 or 64). */
    fun icons(size: Int): BitmapFont = FontLibrary.get("icons-$size")
}

data class Spacing(
    val xs: Int = 4,
    val s: Int = 8,
    val m: Int = 12,
    val l: Int = 16,
    val xl: Int = 24,
    val xxl: Int = 32,
    /** Height of the status line at the top of the screen. */
    val statusBarHeight: Int = 34,
    /** Height of one row in menus and lists. */
    val rowHeight: Int = 52,
    val iconSize: Int = 28,
)

data class Shapes(
    val radiusSmall: Float = 8f,
    val radiusMedium: Float = 14f,
    val radiusLarge: Float = 24f,
    val stroke: Float = 2f,
    val hairline: Float = 1f,
)

data class Motion(
    val enabled: Boolean = true,
    val fastMs: Long = 110,
    val normalMs: Long = 180,
    val slowMs: Long = 320,
    val toastMs: Long = 4000,
)
