package se.blick.app.widget

import androidx.compose.ui.graphics.Color
import se.blick.app.domain.model.TransportMode

/**
 * Stockholm public-transport line-family colors, per SL's own line-color convention: Pendeltåg
 * (commuter rail) lines 40, 41, 42X, 43, 43X, 44, 48 in pink; Metro blue-line 10-11; Metro
 * red-line 13-14; Metro green-line 17-19. Used by [BlickRoutineWidget]'s line-number badge AND
 * [se.blick.app.ui.components.LineBadge] (the same badge reused throughout the rest of the
 * app) — this is the one, shared mapping; neither renderer computes its own. Deliberately
 * independent of [androidx.compose.ui.graphics.Color]/[androidx.glance.unit.ColorProvider] so
 * this mapping itself stays a plain, Android-independent function, testable as a plain JVM
 * unit; [toBadgeColor] converts a [LineBadgeColor] to an actual color value for rendering.
 */
enum class LineBadgeColor {
    /** Pendeltåg (commuter rail, [TransportMode.TRAIN]) lines 40, 41, 42X, 43, 43X, 44, 48. */
    Pink,
    /** Metro blue-line, lines 10-11. */
    Blue,
    /** Metro red-line, lines 13-14. */
    Red,
    /** Metro green-line, lines 17-19. */
    Green,
    /** Every other mode/line combination — rendered as a neutral grey badge, never left
     * uncolored or defaulted to one of the above. */
    Unknown,
}

object LineBadgeColorMapping {

    private val PENDELTAG_LINES = setOf("40", "41", "42X", "43", "43X", "44", "48")
    private val METRO_BLUE_LINES = setOf("10", "11")
    private val METRO_RED_LINES = setOf("13", "14")
    private val METRO_GREEN_LINES = setOf("17", "18", "19")

    /**
     * Color depends on BOTH [mode] and [lineDesignation] — a bus, train, or any other mode
     * that happens to share a metro line's own number (e.g. a bus "14") must never be colored
     * as if it were that metro line, and a metro line that happens to share a Pendeltåg number
     * (e.g. metro "40") must never be colored pink. Every mode/line combination not explicitly
     * listed above (including every OTHER Pendeltåg/Metro line, e.g. Roslagsbanan's own train
     * line numbers) is [LineBadgeColor.Unknown].
     *
     * [lineDesignation] is normalized (trimmed, uppercased) before lookup, so "42x"/" 42X "/
     * "42X" all resolve identically — SL's own "X" (express) line suffix is conventionally
     * uppercase, but real API or user-entered data should never be trusted to already be.
     */
    fun colorFor(mode: TransportMode, lineDesignation: String): LineBadgeColor {
        val normalized = lineDesignation.trim().uppercase()
        return when (mode) {
            TransportMode.TRAIN -> if (normalized in PENDELTAG_LINES) LineBadgeColor.Pink else LineBadgeColor.Unknown
            TransportMode.METRO -> when (normalized) {
                in METRO_BLUE_LINES -> LineBadgeColor.Blue
                in METRO_RED_LINES -> LineBadgeColor.Red
                in METRO_GREEN_LINES -> LineBadgeColor.Green
                else -> LineBadgeColor.Unknown
            }
            else -> LineBadgeColor.Unknown
        }
    }
}

// Darkened from SL's own brighter line-family colors specifically so white badge text stays at
// or above the WCAG AA 4.5:1 contrast minimum for normal-size text — the original, brighter
// values measured at only 3.11 (pink), 4.17 (red), and 2.46 (green) against white, all below
// 4.5, with green badly so. Blue (4.54) and grey (4.83) already passed but blue's own margin was
// razor-thin, so it got a small nudge too, for a safer margin against real-device subpixel/
// anti-aliasing variance rather than a paper-thin pass. Hue is preserved (each channel scaled by
// the same factor toward black) so the SL line-family color is still recognizably the same
// family, just deep enough to stay readable. Exact contrast ratios are asserted directly against
// these literal values in LineBadgeColorMappingTest, so a future edit here that regresses
// contrast fails a test rather than shipping unnoticed. Internal (not private) — shared by
// BlickRoutineWidget's Glance-based badge and se.blick.app.ui.components.LineBadge's standard
// Compose badge, the one pair of renderers this mapping exists for.
internal val LINE_BADGE_PINK = Color(0xFFC73981)
internal val LINE_BADGE_BLUE = Color(0xFF1676B8)
internal val LINE_BADGE_RED = Color(0xFFDB2925)
internal val LINE_BADGE_GREEN = Color(0xFF38803F)
internal val LINE_BADGE_GREY = Color(0xFF6B7280)

internal fun LineBadgeColor.toBadgeColor(): Color = when (this) {
    LineBadgeColor.Pink -> LINE_BADGE_PINK
    LineBadgeColor.Blue -> LINE_BADGE_BLUE
    LineBadgeColor.Red -> LINE_BADGE_RED
    LineBadgeColor.Green -> LINE_BADGE_GREEN
    LineBadgeColor.Unknown -> LINE_BADGE_GREY
}
