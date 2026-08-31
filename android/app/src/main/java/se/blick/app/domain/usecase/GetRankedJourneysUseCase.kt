package se.blick.app.domain.usecase

import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.LiveJourneyOptions
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Fetches this routine's exact-destination journeys and defensively re-filters them for
 * currency — it no longer re-ranks or re-derives PRIMARY/NEXT/ALTERNATIVE roles itself. The
 * backend (see backend/src/routes/journeys.ts) is the sole authority on both role assignment and
 * ordering: identifying the current regular route family, the next departure within it, and any
 * genuine different-family alternative is a structural comparison (matching stop sequences) plus
 * Pareto-dominance filtering over the full upstream candidate set — including targeted follow-up
 * SL searches this use case never sees, only whatever short, already-curated, already-labelled
 * list the backend decided to send. Re-deriving roles from that alone would be exactly the kind
 * of list-position guessing the product spec explicitly rules out. This class's own
 * defense-in-depth layer is therefore narrower than it once was: it can still refuse to display
 * something the backend sent that has since become ineligible (expired, or over the change
 * limit), but it trusts the backend's own order and role for everything that survives that
 * filter.
 */
class GetRankedJourneysUseCase @Inject constructor(
    private val repository: JourneyRepository,
    private val clock: Clock,
) {
    companion object {
        const val MAX_LATER_JOURNEYS = 3
    }
    /**
     * `now` is captured AFTER [repository.getJourneys] returns, never before it's called: the
     * network round-trip itself can take long enough that a journey departing while the request
     * is in flight would otherwise still read as "upcoming" against a now-stale pre-request
     * timestamp — e.g. the request starts at 22:11:59.8, the transport departs at 22:12:00, and
     * the response only arrives at 22:12:00.5; comparing against a `now` read at 22:11:59.8 would
     * wrongly let that journey survive. Filtering (removing anything no longer [isCurrentJourney]
     * at that post-response `now`) mirrors the backend's own equivalent filter — a stale cached
     * response or a future backend regression must never be able to resurface an already-departed
     * or over-the-change-limit journey on this side either. The backend's own order (and each
     * journey's own [se.blick.app.domain.model.JourneyRole]) is otherwise preserved exactly —
     * see this class's own doc.
     *
     * [searchUntil] bounds how far forward the backend's own targeted NEXT/ALTERNATIVE
     * acquisition may search — the current routine occurrence's real active-window end, never an
     * invented horizon. [se.blick.app.scheduling.RoutineActiveWindowWorker] already has this as
     * its own `windowEnd`; the foreground Routine Details screen derives it from
     * [se.blick.app.scheduling.NextOccurrenceCalculator], the same shared scheduling logic every
     * other occurrence calculation in this app already uses, rather than a duplicated one. Null
     * only when a caller genuinely has no such boundary (e.g. [se.blick.app.scheduling.NextOccurrence.None]) —
     * the backend then answers from its own initial acquisition alone rather than searching
     * unboundedly, and defaults to null here so callers that don't have one yet keep compiling.
     *
     * [changesPreference] is the routine's own persisted [ExactDestinationChangesPreference] —
     * forwarded to [repository.getJourneys] unchanged, never inspected or acted on here: this use
     * case still only re-filters for currency (see this class's own doc), never re-ranking or
     * re-deriving eligibility itself, since the backend is the sole authority on which journeys
     * are eligible under a given preference. Defaults to [ExactDestinationChangesPreference.BOTH]
     * — the pre-existing, unfiltered behavior — so a caller predating this parameter keeps
     * compiling and behaving unchanged.
     */
    suspend operator fun invoke(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant? = null,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
    ): List<JourneyPlan> {
        val journeys = repository.getJourneys(originId, destinationId, allowedTransportModes, searchUntil, changesPreference)
        val now = clock.instant()
        return journeys.filterCurrentJourneys(now)
    }

    /** Foreground Routine Details variant. Backend ordering/roles remain authoritative; both
     * lists receive only the same post-response currency/change-limit defense as [invoke]. */
    suspend fun getOptions(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant? = null,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
        laterJourneyCount: Int,
    ): LiveJourneyOptions {
        require(laterJourneyCount in 0..MAX_LATER_JOURNEYS) { "laterJourneyCount must be between 0 and $MAX_LATER_JOURNEYS" }
        val options = repository.getJourneyOptions(
            originId,
            destinationId,
            allowedTransportModes,
            searchUntil,
            changesPreference,
            laterJourneyCount,
        )
        val now = clock.instant()
        val authoritative = options.journeys.filterCurrentJourneys(now)
        val authoritativeIds = authoritative.mapTo(mutableSetOf(), JourneyPlan::journeyId)
        val seen = mutableSetOf<String>()
        val later = options.laterJourneys.filter { option ->
            option.journey.isCurrentJourney(now) &&
                option.journey.journeyId !in authoritativeIds &&
                seen.add(option.journey.journeyId)
        }
        return LiveJourneyOptions(authoritative, later)
    }
}
