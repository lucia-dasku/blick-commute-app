package se.blick.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R
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
 * secondary "alternative" row is never dropped for being compact, only for genuinely having
 * expired or been promoted — otherwise a compact-mode false negative could be mistaken for this
 * bug being fixed.
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
    ) = WidgetJourneyRow(
        lineDesignation = lineDesignation,
        transportMode = transportMode,
        departureTime = departureTime,
        arrivalTime = departureTime.plusSeconds(600),
        transferCount = 0,
        isRealtime = true,
    )

    private fun activeRoutineState(fastest: WidgetJourneyRow, alternative: WidgetJourneyRow?) =
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel(
                routineId = "r1",
                routineName = "Airport commute",
                stationName = "Fruängen",
                directionLabel = "Arlanda",
                content = RoutineWidgetContent.Journeys(fastest, alternative),
                lineDesignation = fastest.lineDesignation,
                transportMode = fastest.transportMode,
            ),
        )

    private fun countdownText(minutes: Long) = context.getString(R.string.widget_countdown_minutes_format, minutes)

    private fun arrivalText(row: WidgetJourneyRow) =
        context.getString(R.string.widget_journey_arrival, arrivalFormatter.format(row.arrivalTime))

    private fun alternativeText(row: WidgetJourneyRow, minutes: Long) = context.getString(
        R.string.widget_journey_alternative, row.lineDesignation.orEmpty(), minutes, arrivalFormatter.format(row.arrivalTime),
    )

    @Test
    fun `both fastest and alternative current -- header shows fastest's line, both countdowns render`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val fastest = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val alternative = journeyRow(now.plusSeconds(420), lineDesignation = "57")
            provideComposable { BlickWidgetContent(activeRoutineState(fastest, alternative), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasText(countdownText(5))).assertExists()
            onNode(hasText(alternativeText(alternative, 7))).assertExists()
        }

    @Test
    fun `only fastest current -- expired alternative is dropped, fastest still leads`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val fastest = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val expiredAlternative = journeyRow(now.minusSeconds(1), lineDesignation = "57")
            provideComposable { BlickWidgetContent(activeRoutineState(fastest, expiredAlternative), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasText(countdownText(5))).assertExists()
            onNode(hasText(alternativeText(expiredAlternative, 0))).assertDoesNotExist()
        }

    @Test
    fun `only alternative current -- promoted into the primary countdown AND the header badge`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val expiredFastest = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val alternative = journeyRow(now.plusSeconds(420), lineDesignation = "57")
            provideComposable { BlickWidgetContent(activeRoutineState(expiredFastest, alternative), now) }

            // Header badge follows the promoted row, not the original (now-expired) fastest.
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertExists()
            // The promoted row drives the PRIMARY countdown/arrival format, not the secondary
            // "alternative" one -- there is nothing left to demote it under.
            onNode(hasText(countdownText(7))).assertExists()
            onNode(hasText(arrivalText(alternative))).assertExists()
            onNode(hasText(alternativeText(alternative, 7))).assertDoesNotExist()
        }

    @Test
    fun `neither current -- unavailable body, no stale line badge for either original row`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            // Different (both expired) departure/arrival instants -- so the two arrival-text
            // absence assertions below are genuinely independent checks, not the same string
            // asked about twice.
            val expiredFastest = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val expiredAlternative = journeyRow(now.minusSeconds(90), lineDesignation = "57")
            provideComposable { BlickWidgetContent(activeRoutineState(expiredFastest, expiredAlternative), now) }

            onNode(hasText(context.getString(R.string.notification_unavailable))).assertExists()
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertDoesNotExist()
            // Neither row is rendered as a live "0 min" countdown, and neither expired journey's
            // own arrival time leaks through under the Unavailable body text.
            onNode(hasText(countdownText(0))).assertDoesNotExist()
            onNode(hasText(arrivalText(expiredFastest))).assertDoesNotExist()
            onNode(hasText(arrivalText(expiredAlternative))).assertDoesNotExist()
        }
}
