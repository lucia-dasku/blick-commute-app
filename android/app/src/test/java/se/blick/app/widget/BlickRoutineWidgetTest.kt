package se.blick.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

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

    // ---- resolveEffectiveModel: render-time alternative-promotion matrix ----
    //
    // Pure JVM tests, same rationale as isCompactLayout above -- RoutineWidgetModel/
    // RoutineWidgetContent/WidgetJourneyRow are plain data classes usable with no Glance/Android
    // dependency at all.

    private val resolveNow = Instant.parse("2026-08-11T08:00:00Z")

    private fun journeyRow(
        departureTime: Instant,
        lineDesignation: String? = "14",
        transportMode: TransportMode = TransportMode.BUS,
        role: JourneyRole = JourneyRole.PRIMARY,
    ) = WidgetJourneyRow(
        lineDesignation = lineDesignation,
        transportMode = transportMode,
        departureTime = departureTime,
        arrivalTime = departureTime.plusSeconds(600),
        transferCount = 0,
        isRealtime = true,
        role = role,
    )

    private fun journeysModel(primary: WidgetJourneyRow, secondary: WidgetJourneyRow?) = RoutineWidgetModel(
        routineId = "r1",
        routineName = "Airport commute",
        stationName = "Fruängen",
        directionLabel = "Arlanda",
        content = RoutineWidgetContent.Journeys(primary, secondary),
        lineDesignation = primary.lineDesignation,
        transportMode = primary.transportMode,
    )

    @Test
    fun `both primary and secondary current -- model returned unchanged`() {
        val primary = journeyRow(resolveNow.plusSeconds(60), lineDesignation = "14", transportMode = TransportMode.BUS)
        val secondary = journeyRow(resolveNow.plusSeconds(120), lineDesignation = "57", transportMode = TransportMode.METRO, role = JourneyRole.NEXT)
        val model = journeysModel(primary, secondary)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(model, resolved)
    }

    @Test
    fun `only primary current -- secondary is dropped, header keeps the primary's own line`() {
        val primary = journeyRow(resolveNow.plusSeconds(60), lineDesignation = "14", transportMode = TransportMode.BUS)
        val expiredSecondary = journeyRow(resolveNow.minusSeconds(1), lineDesignation = "57", transportMode = TransportMode.METRO, role = JourneyRole.NEXT)
        val model = journeysModel(primary, expiredSecondary)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(RoutineWidgetContent.Journeys(primary, null), resolved.content)
        assertEquals("14", resolved.lineDesignation)
        assertEquals(TransportMode.BUS, resolved.transportMode)
    }

    @Test
    fun `only secondary current -- promoted into the primary slot and drives the header badge`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1), lineDesignation = "14", transportMode = TransportMode.BUS)
        val secondary = journeyRow(resolveNow.plusSeconds(120), lineDesignation = "57", transportMode = TransportMode.METRO, role = JourneyRole.NEXT)
        val model = journeysModel(expiredPrimary, secondary)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(RoutineWidgetContent.Journeys(secondary, null), resolved.content)
        assertEquals("57", resolved.lineDesignation)
        assertEquals(TransportMode.METRO, resolved.transportMode)
    }

    @Test
    fun `a promoted NEXT row keeps its own real role -- promotion never silently rewrites it to PRIMARY`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1), role = JourneyRole.PRIMARY)
        val next = journeyRow(resolveNow.plusSeconds(120), role = JourneyRole.NEXT)
        val model = journeysModel(expiredPrimary, next)

        val resolved = resolveEffectiveModel(model, resolveNow)

        val promoted = (resolved.content as RoutineWidgetContent.Journeys).primary
        assertEquals(JourneyRole.NEXT, promoted.role)
    }

    @Test
    fun `a promoted ALTERNATIVE row keeps its own real role -- promotion never silently rewrites it to PRIMARY`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1), role = JourneyRole.PRIMARY)
        val alternative = journeyRow(resolveNow.plusSeconds(120), role = JourneyRole.ALTERNATIVE)
        val model = journeysModel(expiredPrimary, alternative)

        val resolved = resolveEffectiveModel(model, resolveNow)

        val promoted = (resolved.content as RoutineWidgetContent.Journeys).primary
        assertEquals(JourneyRole.ALTERNATIVE, promoted.role)
    }

    @Test
    fun `neither current -- falls back to Unavailable with no line badge`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1), lineDesignation = "14")
        val expiredSecondary = journeyRow(resolveNow.minusSeconds(1), lineDesignation = "57", role = JourneyRole.NEXT)
        val model = journeysModel(expiredPrimary, expiredSecondary)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(RoutineWidgetContent.Unavailable, resolved.content)
        assertNull(resolved.lineDesignation)
    }

    @Test
    fun `an expired primary with no secondary to promote also falls back to Unavailable`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1))
        val model = journeysModel(expiredPrimary, null)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(RoutineWidgetContent.Unavailable, resolved.content)
        assertNull(resolved.lineDesignation)
    }

    @Test
    fun `a departure exactly at now is still current, not yet dropped`() {
        val primary = journeyRow(resolveNow)
        val model = journeysModel(primary, null)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(RoutineWidgetContent.Journeys(primary, null), resolved.content)
    }

    @Test
    fun `non-Journeys content is returned completely unchanged`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Airport commute",
            stationName = "Fruängen",
            directionLabel = "Arlanda",
            content = RoutineWidgetContent.Loading,
            lineDesignation = "14",
            transportMode = TransportMode.BUS,
        )

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(model, resolved)
    }
}
