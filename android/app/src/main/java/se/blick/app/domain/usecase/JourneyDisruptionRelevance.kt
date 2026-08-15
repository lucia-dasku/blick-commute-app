package se.blick.app.domain.usecase

import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole

/**
 * The current PRIMARY journey's own disruption notices, deduplicated by exact text (defensive —
 * the backend already deduplicates identical notices before this ever reaches Android, see
 * `backend/src/normalize/normalizeJourney.ts`'s own doc, but this never assumes that guarantee
 * is the only thing standing between a repeated notice and a repeated card, the same way
 * [se.blick.app.widget.decideJourneysWidgetState] re-filters by time despite its own caller
 * already having done so). Empty when there is no PRIMARY (an empty or failed journey search) or
 * PRIMARY simply has none. Never NEXT's or ALTERNATIVE's own notices — those must never replace
 * PRIMARY's own live relevance (see [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own
 * doc). [this] is expected to already be current-filtered (see [filterCurrentJourneys]) — every
 * real call site already has a current list in hand at the exact point it needs this, so this
 * does not re-filter by time itself. Automatically follows PRIMARY across a refresh: this is a
 * pure derivation from whatever list is passed in, never cached or held onto across calls.
 */
fun List<JourneyPlan>.primaryDisruptionNotices(): List<JourneyDisruptionNotice> =
    firstOrNull { it.role == JourneyRole.PRIMARY }
        ?.disruptionNotices
        ?.distinctBy { it.text }
        ?: emptyList()

/**
 * Conservative single-value aggregation of [this] for the compact notification/widget
 * indicator — never an invented severity ranking: a single DISTINCT notice's own classified
 * effect is used as-is (several duplicate copies of the identical text count as one, matching
 * [primaryDisruptionNotices]'s own dedup); more than one genuinely different notice falls back
 * to the generic [DisruptionEffect.DISRUPTION] label, since there is no upstream-provided way to
 * say which of several distinct Journey Planner notices matters most, and a confidently-wrong
 * "most important" pick would be worse than the honest generic one. Distinctness is re-checked
 * here too (by text), not merely assumed from the caller — this function's own contract must
 * hold regardless of whether [this] happens to already be deduplicated. Routine Details shows
 * every distinct notice individually (see `RoutineDetailsScreen`'s own disruption cards) — only
 * this single compact value collapses them for the notification/widget's one-line indicator.
 * `null` when [this] is empty — nothing to show.
 */
fun List<JourneyDisruptionNotice>.compactPresentation(): DisruptionPresentation? {
    val distinct = distinctBy { it.text }
    val first = distinct.firstOrNull() ?: return null
    val effect = if (distinct.size == 1) first.effect else DisruptionEffect.DISRUPTION
    return DisruptionPresentation(headline = first.text, details = null, effect = effect)
}
