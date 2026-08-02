package se.blick.app.widget

import se.blick.app.domain.model.TransportMode

/**
 * Stockholm public-transport line-family colors, per SL's own line-color convention: Pendeltåg
 * (commuter rail) lines 40, 41, 42X, 43, 43X, 44, 48 in pink; Metro blue-line 10-11; Metro
 * red-line 13-14; Metro green-line 17-19. Used only for [BlickRoutineWidget]'s line-number
 * badge — no other screen colors lines by family. Deliberately independent of
 * [androidx.compose.ui.graphics.Color]/[androidx.glance.unit.ColorProvider] so this mapping
 * itself stays a plain, Android-independent function, testable as a plain JVM unit;
 * `BlickRoutineWidget` converts a [LineBadgeColor] to an actual color value for rendering.
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
