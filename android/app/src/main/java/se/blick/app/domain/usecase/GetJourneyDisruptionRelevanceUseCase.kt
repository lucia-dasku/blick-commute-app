package se.blick.app.domain.usecase

import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.JourneyDisruptionContext
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.ResolvedJourneyDisruption
import java.time.Instant
import javax.inject.Inject

/**
 * Thin wrapper around [JourneyRepository.getRelevantDeviationNotices] — see that function's own
 * doc for exactly what it resolves (the backend's own single authoritative combination of
 * [journeyPlannerNotices] with structurally-matched SL Deviations, from the shared cached
 * snapshot, for the current PRIMARY journey's own transit legs) and why. Does not catch
 * exceptions itself (mirrors [GetRankedJourneysUseCase]'s own convention, unlike
 * [GetDisruptionsUseCase]'s Flow/sealed-state wrapping) — callers (currently only
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] and
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel]) are each already responsible
 * for their own timeout/failure handling around the one call site that needs it, exactly like
 * they already are for [GetRankedJourneysUseCase]'s own call — a failure or timeout here must
 * never prevent the already-fetched PRIMARY journey from being shown; it only means this tick's
 * presentation stays Journey-Planner-notices-only (still correct, just possibly incomplete).
 *
 * [journeyPlannerNotices] is deliberately a caller-supplied parameter rather than re-derived
 * internally from a [se.blick.app.domain.model.JourneyPlan]: every real call site already has
 * [primaryDisruptionNotices]'s own deduplicated result in hand (needed for its own first,
 * primary-only post) at the exact point it needs this too — recomputing it here would be
 * redundant, not more correct.
 *
 * [disruptionContext]/[departureTime]/[arrivalTime] are PRIMARY's own
 * [se.blick.app.domain.model.JourneyPlan.disruptionContext]/`departureTime`/`arrivalTime` — see
 * [JourneyRepository.getRelevantDeviationNotices]'s own doc. Trailing and defaulted to null, like
 * every other addition to this use case, so no existing positional call site elsewhere in this
 * codebase can have a later positional argument silently rebind to a new parameter instead.
 */
class GetJourneyDisruptionRelevanceUseCase @Inject constructor(
    private val repository: JourneyRepository,
) {
    suspend operator fun invoke(
        primaryLegs: List<JourneyLeg>,
        originSiteId: Long?,
        journeyPlannerNotices: List<JourneyDisruptionNotice>,
        disruptionContext: JourneyDisruptionContext? = null,
        departureTime: Instant? = null,
        arrivalTime: Instant? = null,
    ): List<ResolvedJourneyDisruption> =
        repository.getRelevantDeviationNotices(primaryLegs, originSiteId, journeyPlannerNotices, disruptionContext, departureTime, arrivalTime)
}
