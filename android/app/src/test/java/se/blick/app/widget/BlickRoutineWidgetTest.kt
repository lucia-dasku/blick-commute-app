package se.blick.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.R
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * Pure JVM tests for [widgetLayoutRulesFor] and [LineBadgeColor.toBadgeColor] — no Android/Glance
 * dependency, since [androidx.compose.ui.unit.Dp] and [androidx.compose.ui.graphics.Color] are
 * both plain value classes usable outside any composition or Robolectric runtime, matching this
 * project's other pure-function test files.
 */
class BlickRoutineWidgetTest {

    // ---- Exactly three canonical tiers, with safe nearest-smaller fallbacks ----

    @Test
    fun `2x2 bounds select Small with two-line routes and no secondary detail`() {
        val rules = widgetLayoutRulesFor(width = 180.dp, height = 130.dp)

        assertEquals(WidgetLayoutTier.SMALL, rules.tier)
        assertEquals(2, rules.routeMaxLines)
        assertTrue(rules.showRoutineLabel)
        assertFalse(rules.showSecondary)
        assertFalse(rules.showJourneyTimes)
        assertFalse(rules.showDisruption)
    }

    @Test
    fun `a short 2x2 resize omits only the optional label rather than crowding the countdown`() {
        val rules = widgetLayoutRulesFor(width = 180.dp, height = 119.dp)

        assertEquals(WidgetLayoutTier.SMALL, rules.tier)
        assertFalse(rules.showRoutineLabel)
    }

    @Test
    fun `3x2 bounds select Standard with label and Next but no journey times`() {
        val rules = widgetLayoutRulesFor(width = 260.dp, height = 150.dp)

        assertEquals(WidgetLayoutTier.STANDARD, rules.tier)
        assertEquals(1, rules.routeMaxLines)
        assertTrue(rules.showRoutineLabel)
        assertTrue(rules.showSecondary)
        assertFalse(rules.showJourneyTimes)
        assertFalse(rules.showDisruption)
    }

    @Test
    fun `a taller Standard placement preserves the existing size-appropriate disruption strip`() {
        val rules = widgetLayoutRulesFor(width = 260.dp, height = 180.dp)

        assertEquals(WidgetLayoutTier.STANDARD, rules.tier)
        assertTrue(rules.showDisruption)
    }

    @Test
    fun `4x4 bounds select Large with full route times Next and disruption`() {
        val rules = widgetLayoutRulesFor(width = 340.dp, height = 260.dp)

        assertEquals(WidgetLayoutTier.LARGE, rules.tier)
        assertEquals(2, rules.routeMaxLines)
        assertTrue(rules.showRoutineLabel)
        assertTrue(rules.showSecondary)
        assertTrue(rules.showJourneyTimes)
        assertTrue(rules.showDisruption)
    }

    @Test
    fun `large width with insufficient height safely falls back to Standard`() {
        assertEquals(WidgetLayoutTier.STANDARD, widgetLayoutRulesFor(width = 340.dp, height = 219.dp).tier)
    }

    @Test
    fun `large height with insufficient width safely falls back to Standard`() {
        assertEquals(WidgetLayoutTier.STANDARD, widgetLayoutRulesFor(width = 299.dp, height = 260.dp).tier)
    }

    @Test
    fun `narrow launcher bounds safely select Small regardless of extra height`() {
        assertEquals(WidgetLayoutTier.SMALL, widgetLayoutRulesFor(width = 219.dp, height = 260.dp).tier)
    }

    // ---- Inactive-state artwork stays subordinate to content at every supported size. ----

    @Test
    fun `very compact inactive bounds omit skyline while canonical Small uses the approved asset`() {
        val compact = inactiveWidgetLayoutFor(width = 110.dp, height = 80.dp)
        val canonicalSmall = inactiveWidgetLayoutFor(width = 180.dp, height = 130.dp)

        assertNull(compact.skylineResourceId)
        assertEquals(R.drawable.widget_inactive_skyline_approved, canonicalSmall.skylineResourceId)
        assertTrue(inactiveSkylineHeightFor(180.dp, canonicalSmall)!! in 65.dp..66.dp)
        assertTrue(compact.logoViewportHeight < canonicalSmall.logoViewportHeight)
    }

    @Test
    fun `standard inactive bounds show the full skyline at its natural aspect ratio`() {
        val standard = inactiveWidgetLayoutFor(width = 260.dp, height = 150.dp)

        assertEquals(R.drawable.widget_inactive_skyline_approved, standard.skylineResourceId)
        assertTrue(inactiveSkylineHeightFor(260.dp, standard)!! in 94.dp..95.dp)
    }

    @Test
    fun `large inactive bounds expand branding and full-width skyline without adding a new tier`() {
        val standard = inactiveWidgetLayoutFor(width = 260.dp, height = 150.dp)
        val large = inactiveWidgetLayoutFor(width = 340.dp, height = 260.dp)

        assertEquals(R.drawable.widget_inactive_skyline_approved, large.skylineResourceId)
        assertTrue(inactiveSkylineHeightFor(340.dp, large)!! in 123.dp..124.dp)
        assertTrue(large.logoViewportHeight > standard.logoViewportHeight)
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
    // Pure JVM tests, same rationale as widgetLayoutRulesFor above -- RoutineWidgetModel/
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

    private fun journeysModel(
        primary: WidgetJourneyRow,
        secondary: WidgetJourneyRow?,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
    ) = RoutineWidgetModel(
        routineId = "r1",
        routineName = "Airport commute",
        stationName = "Fruängen",
        directionLabel = "Arlanda",
        content = RoutineWidgetContent.Journeys(primary, secondary, changesPreference),
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

    // ---- changesPreference: must survive resolveEffectiveModel's own re-wrapping of the content
    // exactly as-is -- the routine's real persisted preference, never silently reset to the
    // field's own BOTH default on every render (a real regression caught by these two tests). ----

    @Test
    fun `changesPreference survives unchanged when both primary and secondary are current`() {
        val primary = journeyRow(resolveNow.plusSeconds(60))
        val secondary = journeyRow(resolveNow.plusSeconds(120), role = JourneyRole.NEXT)
        val model = journeysModel(primary, secondary, ExactDestinationChangesPreference.DIRECT_ONLY)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(ExactDestinationChangesPreference.DIRECT_ONLY, (resolved.content as RoutineWidgetContent.Journeys).changesPreference)
    }

    @Test
    fun `changesPreference survives unchanged when the secondary row is promoted into the primary slot`() {
        val expiredPrimary = journeyRow(resolveNow.minusSeconds(1))
        val secondary = journeyRow(resolveNow.plusSeconds(120), role = JourneyRole.NEXT)
        val model = journeysModel(expiredPrimary, secondary, ExactDestinationChangesPreference.WITH_CHANGES_ONLY)

        val resolved = resolveEffectiveModel(model, resolveNow)

        assertEquals(ExactDestinationChangesPreference.WITH_CHANGES_ONLY, (resolved.content as RoutineWidgetContent.Journeys).changesPreference)
    }

    @Test
    fun `large widget station captions remove city municipality and region suffixes`() {
        assertEquals("Mälarhöjden", compactWidgetStationName("Mälarhöjden, Stockholm"))
        assertEquals("Tumba", compactWidgetStationName("Tumba, Botkyrka kommun, Stockholms län"))
    }

    @Test
    fun `large widget station captions preserve parenthetical station descriptors`() {
        assertEquals("Arlanda central (pendeltåg)", compactWidgetStationName(" Arlanda central (pendeltåg) "))
    }

    @Test
    fun `phone-sized Large widget station captions stay capped at two lines`() {
        assertEquals(2, largeJourneyStationMaxLinesFor(340.dp))
    }

    @Test
    fun `wide tablet Large widget station captions are not line-capped`() {
        assertEquals(Int.MAX_VALUE, largeJourneyStationMaxLinesFor(500.dp))
    }

    @Test
    fun `Large widget one-change connector is longer than multi-change connectors`() {
        assertEquals(24, largeJourneyConnectorDotsFor(stageCount = 2).length)
        assertEquals(16, largeJourneyConnectorDotsFor(stageCount = 3).length)
    }

    // ---- legBadgesOrFallback: falls back to a single header-derived badge only when legBadges
    // itself is genuinely empty (state persisted by a version predating that field). ----

    @Test
    fun `legBadgesOrFallback returns the real per-leg badges unchanged when present`() {
        val badges = listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS))
        val row = journeyRow(resolveNow, lineDesignation = "14").copy(legBadges = badges)

        assertEquals(badges, row.legBadgesOrFallback())
    }

    @Test
    fun `legBadgesOrFallback falls back to a single badge built from lineDesignation-slash-transportMode when empty`() {
        val row = journeyRow(resolveNow, lineDesignation = "14", transportMode = TransportMode.METRO)

        assertEquals(listOf(WidgetJourneyLegBadge("14", TransportMode.METRO)), row.legBadgesOrFallback())
    }

    @Test
    fun `legBadgesOrFallback is empty, never a single meaningless badge, when lineDesignation itself is null`() {
        val row = journeyRow(resolveNow, lineDesignation = null)

        assertEquals(emptyList<WidgetJourneyLegBadge>(), row.legBadgesOrFallback())
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
