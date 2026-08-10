package se.blick.app.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * Pure JVM round-trip tests for [writeInto]/[toWidgetUiState] — no Android dependency, no Glance
 * widget instance, no DataStore file I/O: [androidx.datastore.preferences.core.Preferences] and
 * [androidx.datastore.preferences.core.MutablePreferences] are plain in-memory Kotlin types.
 */
class RoutineWidgetPreferencesTest {

    private fun roundTrip(state: RoutineWidgetUiState): RoutineWidgetUiState {
        val prefs = mutablePreferencesOf()
        state.writeInto(prefs)
        return prefs.toPreferences().toWidgetUiState()
    }

    @Test
    fun `an empty (never-written) Preferences reads back as NoActiveCommute`() {
        val prefs = mutablePreferencesOf()
        assertEquals(RoutineWidgetUiState.NoActiveCommute, prefs.toPreferences().toWidgetUiState())
    }

    @Test
    fun `NoActiveCommute round-trips`() {
        assertEquals(RoutineWidgetUiState.NoActiveCommute, roundTrip(RoutineWidgetUiState.NoActiveCommute))
    }

    @Test
    fun `Loading round-trips with routine identity fields intact`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
    }

    @Test
    fun `a null directionLabel round-trips as null, not an empty string`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", null, RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.directionLabel)
    }

    @Test
    fun `Live with next and following round-trips exactly`() {
        val next = WidgetDepartureRow("14", "T-Centralen", 3L, isRealTime = true, isCancelled = false)
        val following = WidgetDepartureRow("14", "T-Centralen", 12L, isRealTime = false, isCancelled = false)
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Live(next, following))
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(RoutineWidgetContent.Live(next, following), restored.model.content)
    }

    @Test
    fun `Live with no following round-trips with following null, not a stale leftover row`() {
        val next = WidgetDepartureRow("14", "T-Centralen", 3L, isRealTime = true, isCancelled = false)
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Live(next, following = null))
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(RoutineWidgetContent.Live(next, null), restored.model.content)
    }

    @Test
    fun `a state transition clears the previous state's leftover fields`() {
        val next = WidgetDepartureRow("14", "T-Centralen", 3L, isRealTime = true, isCancelled = false)
        val liveModel = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Live(next, next))
        val prefs = mutablePreferencesOf()
        RoutineWidgetUiState.ActiveRoutine(liveModel).writeInto(prefs)

        // Transition straight to Offline -- the previous Live state's next/following rows must
        // not leak through as stale leftovers.
        RoutineWidgetUiState.ActiveRoutine(liveModel.copy(content = RoutineWidgetContent.Offline)).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(RoutineWidgetContent.Offline, restored.model.content)
    }

    @Test
    fun `Stale with null next and following round-trips correctly, distinct from NoUpcomingDepartures`() {
        val lastCheckedAt = Instant.parse("2026-07-28T08:00:00Z")
        val model = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen",
            RoutineWidgetContent.Stale(next = null, following = null, lastCheckedAt = lastCheckedAt),
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        val content = restored.model.content
        assertEquals(RoutineWidgetContent.Stale(null, null, lastCheckedAt), content)
    }

    @Test
    fun `NoUpcomingDepartures round-trips its lastCheckedAt`() {
        val lastCheckedAt = Instant.parse("2026-07-28T08:00:00Z")
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.NoUpcomingDepartures(lastCheckedAt))
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(RoutineWidgetContent.NoUpcomingDepartures(lastCheckedAt), restored.model.content)
    }

    @Test
    fun `Offline and Unavailable round-trip`() {
        val offline = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Offline)
        val unavailable = offline.copy(content = RoutineWidgetContent.Unavailable)
        assertEquals(RoutineWidgetContent.Offline, (roundTrip(RoutineWidgetUiState.ActiveRoutine(offline)) as RoutineWidgetUiState.ActiveRoutine).model.content)
        assertEquals(RoutineWidgetContent.Unavailable, (roundTrip(RoutineWidgetUiState.ActiveRoutine(unavailable)) as RoutineWidgetUiState.ActiveRoutine).model.content)
    }

    @Test
    fun `NotificationsUnavailable round-trips with routine identity fields intact`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.NotificationsUnavailable)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
    }

    @Test
    fun `a transition from NotificationsUnavailable to Live clears no leftover departure rows`() {
        val prefs = mutablePreferencesOf()
        val base = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.NotificationsUnavailable)
        RoutineWidgetUiState.ActiveRoutine(base).writeInto(prefs)

        val next = WidgetDepartureRow("14", "T-Centralen", 3L, isRealTime = true, isCancelled = false)
        RoutineWidgetUiState.ActiveRoutine(base.copy(content = RoutineWidgetContent.Live(next, null))).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(RoutineWidgetContent.Live(next, null), restored.model.content)
    }

    // ---- lineDesignation / transportMode -- drive the header line badge ----

    @Test
    fun `lineDesignation and transportMode round-trip exactly, for the header line badge`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            lineDesignation = "42X",
            transportMode = TransportMode.TRAIN,
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
        assertEquals("42X", restored.model.lineDesignation)
        assertEquals(TransportMode.TRAIN, restored.model.transportMode)
    }

    @Test
    fun `a null lineDesignation round-trips as null, not an empty string`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            lineDesignation = null,
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.lineDesignation)
    }

    @Test
    fun `a model with the default UNKNOWN transportMode round-trips correctly`() {
        // RoutineWidgetModel's own transportMode default (see its own doc: also covers decoding
        // a widget instance whose prefs were written by an app version before this key existed).
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(TransportMode.UNKNOWN, restored.model.transportMode)
    }

    // ---- disruptionHeadline -- drives the bottom disruption strip ----

    @Test
    fun `disruptionHeadline round-trips exactly, for the bottom disruption strip`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            disruptionHeadline = "Delays on line 14",
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
        assertEquals("Delays on line 14", restored.model.disruptionHeadline)
    }

    @Test
    fun `a null disruptionHeadline round-trips as null, not an empty string`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionHeadline)
    }

    @Test
    fun `a transition to a state with no disruption clears a previous state's leftover headline`() {
        val prefs = mutablePreferencesOf()
        val withDisruption = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Delays on line 14",
        )
        RoutineWidgetUiState.ActiveRoutine(withDisruption).writeInto(prefs)

        RoutineWidgetUiState.ActiveRoutine(withDisruption.copy(disruptionHeadline = null)).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionHeadline)
    }

    @Test
    fun `exact-destination fastest and alternative journeys round-trip for compact and large layouts`() {
        val fastest = WidgetJourneyRow(
            lineDesignation = "14",
            transportMode = TransportMode.METRO,
            departureTime = Instant.parse("2026-08-10T07:03:00Z"),
            arrivalTime = Instant.parse("2026-08-10T07:31:00Z"),
            transferCount = 0,
            isRealtime = true,
        )
        val alternative = WidgetJourneyRow(
            lineDesignation = "4",
            transportMode = TransportMode.BUS,
            departureTime = Instant.parse("2026-08-10T07:06:00Z"),
            arrivalTime = Instant.parse("2026-08-10T07:36:00Z"),
            transferCount = 1,
            isRealtime = false,
        )
        val model = RoutineWidgetModel(
            routineId = "exact-1",
            routineName = "To work",
            stationName = "Fruangen",
            directionLabel = "Slussen",
            content = RoutineWidgetContent.Journeys(fastest, alternative),
            lineDesignation = fastest.lineDesignation,
            transportMode = fastest.transportMode,
        )

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(model, restored.model)
        assertEquals(RoutineWidgetContent.Journeys(fastest, alternative), restored.model.content)
    }
}
