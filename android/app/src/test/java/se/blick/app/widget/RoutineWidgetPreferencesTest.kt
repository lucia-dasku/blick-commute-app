package se.blick.app.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineLabel
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
    fun `the saved routine label round-trips exactly`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruangen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            label = RoutineLabel.STUDY,
        )

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(RoutineLabel.STUDY, restored.model.label)
    }

    @Test
    fun `a missing routine label remains null for previously persisted widget state`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruangen", "T-Centralen", RoutineWidgetContent.Loading)

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertNull(restored.model.label)
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

    // ---- disruptionUncertainLineDesignations -- drives the conservative "Line 11 disruption"
    // strip label instead of the raw headline (see that field's own doc). ----

    @Test
    fun `disruptionUncertainLineDesignations round-trips exactly, for the conservative strip label`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            disruptionHeadline = "Trafiken är stängd mellan T-Centralen och Kungsträdgården",
            disruptionUncertainLineDesignations = listOf("11"),
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
        assertEquals(listOf("11"), restored.model.disruptionUncertainLineDesignations)
    }

    @Test
    fun `multiple matched line designations round-trip in order`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            disruptionHeadline = "Trafikstörning",
            disruptionUncertainLineDesignations = listOf("11", "17"),
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(listOf("11", "17"), restored.model.disruptionUncertainLineDesignations)
    }

    @Test
    fun `an empty disruptionUncertainLineDesignations round-trips as empty, not omitted or crashing, matching the default`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(emptyList<String>(), restored.model.disruptionUncertainLineDesignations)
    }

    @Test
    fun `disruptionUncertainLineDesignations persisted by a version predating this field decodes to empty, never crashes`() {
        val prefs = mutablePreferencesOf()
        val withDesignations = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Trafiken är stängd",
            disruptionUncertainLineDesignations = listOf("11"),
        )
        RoutineWidgetUiState.ActiveRoutine(withDesignations).writeInto(prefs)
        prefs.remove(stringPreferencesKey("disruptionUncertainLineDesignations"))

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(emptyList<String>(), restored.model.disruptionUncertainLineDesignations)
        // The rest of the disruption (the CONFIRMED-equivalent headline) is unaffected by the
        // missing key -- only the uncertainty signal itself decodes to empty.
        assertEquals("Trafiken är stängd", restored.model.disruptionHeadline)
    }

    @Test
    fun `a transition to a state with no disruption clears a previous state's leftover uncertain line designations`() {
        val prefs = mutablePreferencesOf()
        val withDesignations = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Trafiken är stängd",
            disruptionUncertainLineDesignations = listOf("11"),
        )
        RoutineWidgetUiState.ActiveRoutine(withDesignations).writeInto(prefs)

        RoutineWidgetUiState.ActiveRoutine(
            withDesignations.copy(disruptionHeadline = null, disruptionUncertainLineDesignations = emptyList()),
        ).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(emptyList<String>(), restored.model.disruptionUncertainLineDesignations)
    }

    // ---- disruptionEffect -- drives the classified category label in the disruption strip,
    // INSTEAD of disruptionHeadline's raw SL text (see that field's own doc). ----

    @Test
    fun `disruptionEffect round-trips exactly, for the classified category label`() {
        val model = RoutineWidgetModel(
            routineId = "r1",
            routineName = "Morning commute",
            stationName = "Fruängen",
            directionLabel = "T-Centralen",
            content = RoutineWidgetContent.Loading,
            disruptionHeadline = "Hissen är ur funktion.",
            disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
        )
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertEquals(model, restored.model)
        assertEquals(DisruptionEffect.ACCESSIBILITY_ISSUE, restored.model.disruptionEffect)
    }

    @Test
    fun `a null disruptionEffect round-trips as null, not defaulted to DISRUPTION`() {
        val model = RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading)
        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionEffect)
    }

    @Test
    fun `every DisruptionEffect value round-trips exactly`() {
        for (effect in DisruptionEffect.entries) {
            val model = RoutineWidgetModel(
                "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
                disruptionHeadline = "Some disruption",
                disruptionEffect = effect,
            )
            val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
            assertEquals(effect, restored.model.disruptionEffect)
        }
    }

    @Test
    fun `disruptionEffect persisted by a version predating this field decodes to null, never crashes -- distinct from an unrecognized value`() {
        val prefs = mutablePreferencesOf()
        val withEffect = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Hissen är ur funktion.",
            disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
        )
        RoutineWidgetUiState.ActiveRoutine(withEffect).writeInto(prefs)
        // Simulate a pre-upgrade write: the key was never present at all.
        prefs.remove(stringPreferencesKey("disruptionEffect"))

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionEffect)
        // The headline itself is unaffected by the missing key -- only its classification is
        // unknown, until the worker's next tick overwrites it with a real effect.
        assertEquals("Hissen är ur funktion.", restored.model.disruptionHeadline)
    }

    @Test
    fun `an unrecognized persisted disruptionEffect value degrades to DISRUPTION, never crashes`() {
        val prefs = mutablePreferencesOf()
        val model = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Något har hänt.",
            disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
        )
        RoutineWidgetUiState.ActiveRoutine(model).writeInto(prefs)
        // Simulate a NEWER app version's DisruptionEffect value, read by this OLDER one -- unlike
        // an absent key (the previous test), the key is genuinely present here.
        prefs[stringPreferencesKey("disruptionEffect")] = "SOME_FUTURE_EFFECT"

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(DisruptionEffect.DISRUPTION, restored.model.disruptionEffect)
    }

    @Test
    fun `a transition to a state with no disruption clears a previous state's leftover effect`() {
        val prefs = mutablePreferencesOf()
        val withDisruption = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Hissen är ur funktion.",
            disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
        )
        RoutineWidgetUiState.ActiveRoutine(withDisruption).writeInto(prefs)

        RoutineWidgetUiState.ActiveRoutine(withDisruption.copy(disruptionHeadline = null, disruptionEffect = null)).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionEffect)
    }

    @Test
    fun `a state transition with no disruption clears headline, effect, and uncertain line designations together`() {
        val prefs = mutablePreferencesOf()
        val withDisruption = RoutineWidgetModel(
            "r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Loading,
            disruptionHeadline = "Trafiken är stängd mellan T-Centralen och Kungsträdgården",
            disruptionUncertainLineDesignations = listOf("11"),
            disruptionEffect = DisruptionEffect.NO_SERVICE,
        )
        RoutineWidgetUiState.ActiveRoutine(withDisruption).writeInto(prefs)

        // Transition straight to Offline -- like the earlier "leftover fields" test above, no
        // disruption fields are set on the new state at all.
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel("r1", "Morning commute", "Fruängen", "T-Centralen", RoutineWidgetContent.Offline),
        ).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertNull(restored.model.disruptionHeadline)
        assertNull(restored.model.disruptionEffect)
        assertTrue(restored.model.disruptionUncertainLineDesignations.isEmpty())
    }

    @Test
    fun `exact-destination primary and secondary journeys round-trip for compact and large layouts`() {
        val primary = WidgetJourneyRow(
            lineDesignation = "14",
            transportMode = TransportMode.METRO,
            departureTime = Instant.parse("2026-08-10T07:03:00Z"),
            arrivalTime = Instant.parse("2026-08-10T07:31:00Z"),
            transferCount = 0,
            isRealtime = true,
            role = JourneyRole.PRIMARY,
        )
        val secondary = WidgetJourneyRow(
            lineDesignation = "4",
            transportMode = TransportMode.BUS,
            departureTime = Instant.parse("2026-08-10T07:06:00Z"),
            arrivalTime = Instant.parse("2026-08-10T07:36:00Z"),
            transferCount = 1,
            isRealtime = false,
            role = JourneyRole.ALTERNATIVE,
        )
        val model = RoutineWidgetModel(
            routineId = "exact-1",
            routineName = "To work",
            stationName = "Fruangen",
            directionLabel = "Slussen",
            content = RoutineWidgetContent.Journeys(primary, secondary),
            lineDesignation = primary.lineDesignation,
            transportMode = primary.transportMode,
        )

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(model, restored.model)
        assertEquals(RoutineWidgetContent.Journeys(primary, secondary), restored.model.content)
    }

    // ---- WidgetJourneyRow.role -- backend-authoritative, must survive persistence exactly,
    // never re-derived or defaulted once genuinely written ----

    @Test
    fun `the primary row's role round-trips exactly`() {
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, null))

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(JourneyRole.PRIMARY, (restored.model.content as RoutineWidgetContent.Journeys).primary.role)
    }

    @Test
    fun `a NEXT secondary role round-trips as NEXT, not ALTERNATIVE`() {
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        val next = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:33:00Z"), Instant.parse("2026-08-10T08:01:00Z"),
            0, isRealtime = false, role = JourneyRole.NEXT,
        )
        val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, next))

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(JourneyRole.NEXT, (restored.model.content as RoutineWidgetContent.Journeys).secondary?.role)
    }

    @Test
    fun `an ALTERNATIVE secondary role round-trips as ALTERNATIVE, not NEXT`() {
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        val alternative = WidgetJourneyRow(
            "4", TransportMode.BUS, Instant.parse("2026-08-10T07:06:00Z"), Instant.parse("2026-08-10T07:36:00Z"),
            1, isRealtime = false, role = JourneyRole.ALTERNATIVE,
        )
        val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, alternative))

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

        assertEquals(JourneyRole.ALTERNATIVE, (restored.model.content as RoutineWidgetContent.Journeys).secondary?.role)
    }

    @Test
    fun `a secondary role persisted by a version predating this field defaults to NEXT on read, never crashes`() {
        val prefs = mutablePreferencesOf()
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        val alternative = WidgetJourneyRow(
            "4", TransportMode.BUS, Instant.parse("2026-08-10T07:06:00Z"), Instant.parse("2026-08-10T07:36:00Z"),
            1, isRealtime = false, role = JourneyRole.ALTERNATIVE,
        )
        val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, alternative))
        RoutineWidgetUiState.ActiveRoutine(model).writeInto(prefs)
        // Simulate a pre-upgrade write: the role keys were never present at all.
        prefs.remove(stringPreferencesKey("journeyPrimaryRole"))
        prefs.remove(stringPreferencesKey("journeySecondaryRole"))

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        val content = restored.model.content as RoutineWidgetContent.Journeys

        assertEquals(JourneyRole.PRIMARY, content.primary.role)
        assertEquals(JourneyRole.NEXT, content.secondary?.role)
    }

    // ---- changesPreference -- the single source of truth BlickRoutineWidget's own
    // Direct/Both/With-changes layout selection reads (see RoutineWidgetContent.Journeys's own
    // doc); must survive persistence exactly, never re-derived or defaulted once genuinely
    // written. ----

    @Test
    fun `changesPreference round-trips exactly for each of the three values`() {
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        for (preference in ExactDestinationChangesPreference.entries) {
            val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, null, preference))

            val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine

            assertEquals(preference, (restored.model.content as RoutineWidgetContent.Journeys).changesPreference)
        }
    }

    @Test
    fun `a changesPreference persisted by a version predating this field defaults to BOTH on read, never crashes`() {
        val prefs = mutablePreferencesOf()
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        val model = RoutineWidgetModel(
            "exact-1", "To work", "Fruangen", "Slussen",
            RoutineWidgetContent.Journeys(primary, null, ExactDestinationChangesPreference.WITH_CHANGES_ONLY),
        )
        RoutineWidgetUiState.ActiveRoutine(model).writeInto(prefs)
        // Simulate a pre-upgrade write: the key was never present at all.
        prefs.remove(stringPreferencesKey("journeyChangesPreference"))

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine

        assertEquals(ExactDestinationChangesPreference.BOTH, (restored.model.content as RoutineWidgetContent.Journeys).changesPreference)
    }

    // ---- legBadges -- one badge per public-transport leg, round-tripped in order. ----

    @Test
    fun `legBadges round-trips in order for a multi-leg primary and secondary`() {
        val primaryBadges = listOf(
            WidgetJourneyLegBadge("14", TransportMode.METRO, "Fruängen"),
            WidgetJourneyLegBadge("40", TransportMode.BUS, "Slussen: terminal | south"),
        )
        val secondaryBadges = listOf(WidgetJourneyLegBadge("17", TransportMode.METRO, "T-Centralen"))
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            1, isRealtime = true, role = JourneyRole.PRIMARY, legBadges = primaryBadges,
        )
        val secondary = WidgetJourneyRow(
            "17", TransportMode.METRO, Instant.parse("2026-08-10T07:33:00Z"), Instant.parse("2026-08-10T08:01:00Z"),
            0, isRealtime = false, role = JourneyRole.NEXT, legBadges = secondaryBadges,
        )
        val model = RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, secondary))

        val restored = roundTrip(RoutineWidgetUiState.ActiveRoutine(model)) as RoutineWidgetUiState.ActiveRoutine
        val content = restored.model.content as RoutineWidgetContent.Journeys

        assertEquals(primaryBadges, content.primary.legBadges)
        assertEquals(secondaryBadges, content.secondary?.legBadges)
    }

    @Test
    fun `an empty legBadges round-trips as empty, not a stale leftover from a previous state`() {
        val prefs = mutablePreferencesOf()
        val withBadges = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            1, isRealtime = true, role = JourneyRole.PRIMARY,
            legBadges = listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
        )
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(withBadges, null)),
        ).writeInto(prefs)

        val withoutBadges = withBadges.copy(legBadges = emptyList())
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(withoutBadges, null)),
        ).writeInto(prefs)

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(emptyList<WidgetJourneyLegBadge>(), (restored.model.content as RoutineWidgetContent.Journeys).primary.legBadges)
    }

    @Test
    fun `legBadges persisted by a version predating this field decodes to empty, never crashes`() {
        val prefs = mutablePreferencesOf()
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            0, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, null)),
        ).writeInto(prefs)
        prefs.remove(stringPreferencesKey("journeyPrimaryLegBadges"))

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        assertEquals(emptyList<WidgetJourneyLegBadge>(), (restored.model.content as RoutineWidgetContent.Journeys).primary.legBadges)
    }

    @Test
    fun `legacy legBadge entries decode without boarding stations`() {
        val prefs = mutablePreferencesOf()
        val primary = WidgetJourneyRow(
            "14", TransportMode.METRO, Instant.parse("2026-08-10T07:03:00Z"), Instant.parse("2026-08-10T07:31:00Z"),
            1, isRealtime = true, role = JourneyRole.PRIMARY,
        )
        RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel("exact-1", "To work", "Fruangen", "Slussen", RoutineWidgetContent.Journeys(primary, null)),
        ).writeInto(prefs)
        prefs[stringPreferencesKey("journeyPrimaryLegBadges")] = "14:METRO|40:BUS"

        val restored = prefs.toPreferences().toWidgetUiState() as RoutineWidgetUiState.ActiveRoutine
        val restoredPrimary = (restored.model.content as RoutineWidgetContent.Journeys).primary

        assertEquals(
            listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
            restoredPrimary.legBadges,
        )
    }

    @Test
    fun `Stockholm night widget theme defaults off and survives content rewrites`() {
        val prefs = mutablePreferencesOf()
        assertEquals(false, prefs.toPreferences().usesStockholmNightWidgetTheme())

        prefs.setStockholmNightWidgetTheme(true)
        RoutineWidgetUiState.NoActiveCommute.writeInto(prefs)

        assertEquals(true, prefs.toPreferences().usesStockholmNightWidgetTheme())
        prefs.setStockholmNightWidgetTheme(false)
        assertEquals(false, prefs.toPreferences().usesStockholmNightWidgetTheme())
    }

    @Test
    fun `system night widget theme is absent by default and survives content rewrites`() {
        val prefs = mutablePreferencesOf()
        assertEquals(null, prefs.toPreferences().systemNightWidgetThemeOrNull())

        prefs.setSystemNightWidgetTheme(true)
        RoutineWidgetUiState.NoActiveCommute.writeInto(prefs)

        assertEquals(true, prefs.toPreferences().systemNightWidgetThemeOrNull())
        prefs.setSystemNightWidgetTheme(false)
        assertEquals(false, prefs.toPreferences().systemNightWidgetThemeOrNull())
    }
}
