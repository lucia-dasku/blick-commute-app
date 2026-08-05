package se.blick.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [isCompactLayout] and [LineBadgeColor.toBadgeColor] — no Android/Glance
 * dependency, since [androidx.compose.ui.unit.Dp] and [androidx.compose.ui.graphics.Color] are
 * both plain value classes usable outside any composition or Robolectric runtime, matching this
 * project's other pure-function test files.
 */
class BlickRoutineWidgetTest {

    // ---- isCompactLayout: width AND height each independently force compact ----

    @Test
    fun `a short and wide size is compact -- height alone is below threshold`() {
        assertTrue(isCompactLayout(width = 300.dp, height = 80.dp))
    }

    @Test
    fun `a narrow and tall size is compact -- width alone is below threshold`() {
        assertTrue(isCompactLayout(width = 150.dp, height = 300.dp))
    }

    @Test
    fun `both dimensions comfortably above their thresholds is not compact`() {
        assertFalse(isCompactLayout(width = 300.dp, height = 200.dp))
    }

    @Test
    fun `both dimensions below their thresholds is still just compact, not double-compact`() {
        assertTrue(isCompactLayout(width = 150.dp, height = 80.dp))
    }

    @Test
    fun `exactly at the height threshold is not yet compact by height`() {
        assertFalse(isCompactLayout(width = 300.dp, height = 110.dp))
    }

    @Test
    fun `just below the height threshold is compact`() {
        assertTrue(isCompactLayout(width = 300.dp, height = 109.dp))
    }

    @Test
    fun `exactly at the width threshold is not yet compact by width`() {
        assertFalse(isCompactLayout(width = 220.dp, height = 200.dp))
    }

    @Test
    fun `just below the width threshold is compact`() {
        assertTrue(isCompactLayout(width = 219.dp, height = 200.dp))
    }

    // ---- res/xml/blick_routine_widget_info_compact.xml's own declared maxResizeWidth/
    // maxResizeHeight must stay strictly below this function's thresholds, not merely equal to
    // them -- an earlier version of that file capped at exactly 220dp/110dp, which this
    // function's own "exactly at the threshold is not yet compact" tests above already prove is
    // one dp too permissive: at precisely that size, the real widget would render the FULLER
    // layout at its own declared maximum resize, the one size the "Compact" picker entry most
    // needs to stay compact. These two constants are hardcoded to match that XML file's own
    // android:maxResizeWidth/maxResizeHeight exactly (not read from the resource itself, which
    // would need a Robolectric/Android Context) specifically so a future edit to either side
    // without the other has a fair chance of being caught here.
    private val compactProviderMaxResizeWidth = 219.dp
    private val compactProviderMaxResizeHeight = 109.dp

    @Test
    fun `the compact provider's own declared maximum resize stays compact`() {
        assertTrue(isCompactLayout(compactProviderMaxResizeWidth, compactProviderMaxResizeHeight))
    }

    // ---- Line-badge colors: WCAG AA 4.5:1 contrast against the white badge text ----
    //
    // Reimplements the WCAG relative-luminance/contrast-ratio formula directly here (rather than
    // depending on a production utility, since none exists in this codebase) so a future edit to
    // BADGE_PINK/BLUE/RED/GREEN/GREY that regresses contrast fails a test instead of shipping
    // unnoticed. See BlickRoutineWidget.kt's own comment above these constants for the exact
    // measured ratios of the original, brighter colors this replaced.

    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private val white = Color.White
    private val wcagAaNormalTextMinimum = 4.5

    @Test
    fun `every line-badge color meets WCAG AA contrast against white badge text`() {
        LineBadgeColor.entries.forEach { badgeColor ->
            val ratio = contrastRatio(badgeColor.toBadgeColor(), white)
            assertTrue(
                "expected $badgeColor's badge color to have contrast >= $wcagAaNormalTextMinimum against white, was $ratio",
                ratio >= wcagAaNormalTextMinimum,
            )
        }
    }

    @Test
    fun `line-badge colors are the exact, deliberately-darkened values`() {
        assertEquals(Color(0xFFC73981), LineBadgeColor.Pink.toBadgeColor())
        assertEquals(Color(0xFF1676B8), LineBadgeColor.Blue.toBadgeColor())
        assertEquals(Color(0xFFDB2925), LineBadgeColor.Red.toBadgeColor())
        assertEquals(Color(0xFF38803F), LineBadgeColor.Green.toBadgeColor())
        assertEquals(Color(0xFF6B7280), LineBadgeColor.Unknown.toBadgeColor())
    }
}
