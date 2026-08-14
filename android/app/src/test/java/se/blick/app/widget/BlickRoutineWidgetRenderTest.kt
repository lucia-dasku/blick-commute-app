package se.blick.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders [BlickWidgetContent] — the exact composable [BlickRoutineWidget.provideGlance] calls —
 * through Glance's own real unit-test rendering pipeline ([runGlanceAppWidgetUnitTest]), so these
 * prove what the widget actually draws, not a re-implementation of [resolveEffectiveModel]'s own
 * selection rules. [BlickRoutineWidgetTest] already covers [resolveEffectiveModel] as a pure
 * function in isolation; this file is the complementary proof that [ActiveRoutineContent] (via
 * [BlickWidgetContent]) truly calls it and that both the header badge and the body agree on
 * whatever it resolves — see [resolveEffectiveModel]'s own doc for the full 4-case matrix.
 *
 * A non-compact size (see [isCompactLayout]'s own thresholds) is used throughout so the
 * secondary row is never dropped for being compact, only for genuinely having expired or been
 * promoted — otherwise a compact-mode false negative could be mistaken for this bug being fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BlickRoutineWidgetRenderTest {

    private val context = RuntimeEnvironment.getApplication()
    // A fixed instant, passed explicitly to BlickWidgetContent's own `now` parameter below (see
    // that function's own doc) -- BlickWidgetContent no longer defaults it internally once a
    // caller supplies one, and ActiveRoutineContent no longer reads java.time.Instant.now() of
    // its own at all, so there is nothing left for this fixture to race against. (Previously this
    // was Instant.now(), relying on this read and ActiveRoutineContent's own separate internal
    // read landing close enough together in real time -- a genuine, if narrow, source of test
    // flakiness that threading `now` as an explicit parameter removes entirely.)
    private val now = Instant.parse("2026-08-11T08:00:00Z")
    private val arrivalFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private fun journeyRow(
        departureTime: Instant,
        lineDesignation: String,
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

    private fun activeRoutineState(primary: WidgetJourneyRow, secondary: WidgetJourneyRow?) =
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel(
                routineId = "r1",
                routineName = "Airport commute",
                stationName = "Fruängen",
                directionLabel = "Arlanda",
                content = RoutineWidgetContent.Journeys(primary, secondary),
                lineDesignation = primary.lineDesignation,
                transportMode = primary.transportMode,
            ),
        )

    private fun countdownText(minutes: Long) = context.getString(R.string.widget_countdown_minutes_format, minutes)

    private fun arrivalText(row: WidgetJourneyRow) =
        context.getString(R.string.widget_journey_arrival, arrivalFormatter.format(row.arrivalTime))

    private fun nextText(row: WidgetJourneyRow, minutes: Long) = context.getString(
        R.string.widget_journey_next, row.lineDesignation.orEmpty(), minutes, arrivalFormatter.format(row.arrivalTime),
    )

    private fun alternativeText(row: WidgetJourneyRow, minutes: Long) = context.getString(
        R.string.widget_journey_alternative, row.lineDesignation.orEmpty(), minutes, arrivalFormatter.format(row.arrivalTime),
    )

    @Test
    fun `both primary and secondary current -- header shows primary's line, both countdowns render`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, next), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasText(countdownText(5))).assertExists()
            onNode(hasText(nextText(next, 7))).assertExists()
        }

    @Test
    fun `only primary current -- expired secondary is dropped, primary still leads`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val expiredSecondary = journeyRow(now.minusSeconds(1), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, expiredSecondary), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasText(countdownText(5))).assertExists()
            onNode(hasText(nextText(expiredSecondary, 0))).assertDoesNotExist()
        }

    @Test
    fun `only secondary current -- promoted into the primary countdown AND the header badge, keeping its own role`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val expiredPrimary = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(expiredPrimary, next), now) }

            // Header badge follows the promoted row, not the original (now-expired) primary.
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertExists()
            // The promoted row drives the PRIMARY countdown/arrival format, not the secondary
            // NEXT one -- there is nothing left to demote it under.
            onNode(hasText(countdownText(7))).assertExists()
            onNode(hasText(arrivalText(next))).assertExists()
            onNode(hasText(nextText(next, 7))).assertDoesNotExist()
        }

    @Test
    fun `neither current -- unavailable body, no stale line badge for either original row`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            // Different (both expired) departure/arrival instants -- so the two arrival-text
            // absence assertions below are genuinely independent checks, not the same string
            // asked about twice.
            val expiredPrimary = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val expiredSecondary = journeyRow(now.minusSeconds(90), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(expiredPrimary, expiredSecondary), now) }

            onNode(hasText(context.getString(R.string.notification_unavailable))).assertExists()
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertDoesNotExist()
            // Neither row is rendered as a live "0 min" countdown, and neither expired journey's
            // own arrival time leaks through under the Unavailable body text.
            onNode(hasText(countdownText(0))).assertDoesNotExist()
            onNode(hasText(arrivalText(expiredPrimary))).assertDoesNotExist()
            onNode(hasText(arrivalText(expiredSecondary))).assertDoesNotExist()
        }

    // ---- Backend-authoritative role decides the second row's own wording -- never assumed from
    // list position, and never the same string for both cases (see JourneyMainContent's own
    // doc). ----

    @Test
    fun `a NEXT-role secondary row renders with the existing NEXT wording, not the Alternative one`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "14", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, next), now) }

            // hasTextEqualTo, not the substring-matching hasText: widget_journey_alternative is
            // deliberately "Alternative: " + widget_journey_next's own format, so the NEXT text
            // is always a substring of the ALTERNATIVE one -- only an exact-text check can tell
            // the two apart here.
            onNode(hasTextEqualTo(nextText(next, 7))).assertExists()
            onNode(hasTextEqualTo(alternativeText(next, 7))).assertDoesNotExist()
        }

    @Test
    fun `an ALTERNATIVE-role secondary row visibly renders as an alternative, not the plain NEXT wording`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val alternative = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.ALTERNATIVE)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, alternative), now) }

            onNode(hasTextEqualTo(alternativeText(alternative, 7))).assertExists()
            onNode(hasTextEqualTo(nextText(alternative, 7))).assertDoesNotExist()
        }
}
