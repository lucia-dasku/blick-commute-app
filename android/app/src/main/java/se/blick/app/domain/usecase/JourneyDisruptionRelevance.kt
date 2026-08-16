package se.blick.app.domain.usecase

import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.DisruptionRelevance
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.toPresentation

/**
 * The current PRIMARY journey's own Journey Planner disruption notices, deduplicated by exact
 * text (defensive — the backend already deduplicates identical notices before this ever reaches
 * Android, see `backend/src/normalize/normalizeJourney.ts`'s own doc, but this never assumes that
 * guarantee is the only thing standing between a repeated notice and a repeated card, the same way
 * [se.blick.app.widget.decideJourneysWidgetState] re-filters by time despite its own caller
 * already having done so). Empty when there is no PRIMARY (an empty or failed journey search) or
 * PRIMARY simply has none. Never NEXT's or ALTERNATIVE's own notices — those must never replace
 * PRIMARY's own live relevance (see [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own
 * doc). [this] is expected to already be current-filtered (see [filterCurrentJourneys]) — every
 * real call site already has a current list in hand at the exact point it needs this, so this
 * does not re-filter by time itself. Automatically follows PRIMARY across a refresh: this is a
 * pure derivation from whatever list is passed in, never cached or held onto across calls.
 *
 * This is the ONE remaining Android-side use of the plain [JourneyDisruptionNotice] shape: it is
 * sent, unchanged, as the `journeyPlannerNotices` field of the request to
 * `POST /api/v1/journeys/disruptions` (see
 * [se.blick.app.data.repository.JourneyRepository.getRelevantDeviationNotices]) — the backend's
 * own [ResolvedJourneyDisruption] resolver is what combines it with matched SL Deviations; Android
 * never performs that combination itself (see this file's own [compactPresentation] doc for the
 * function that used to do so, now removed).
 */
fun List<JourneyPlan>.primaryDisruptionNotices(): List<JourneyDisruptionNotice> =
    firstOrNull { it.role == JourneyRole.PRIMARY }
        ?.disruptionNotices
        ?.distinctBy { it.text }
        ?: emptyList()

/**
 * Conservative single-value aggregation of [this] — the backend's own fully-resolved, deduplicated
 * [ResolvedJourneyDisruption] list (see
 * [se.blick.app.data.repository.JourneyRepository.getRelevantDeviationNotices]) — for the compact
 * notification/widget indicator. Never an invented severity ranking, and never a presentation that
 * claims more confidence than the backend itself established:
 *
 * - A single DISTINCT entry is presented via [ResolvedJourneyDisruption.toPresentation] exactly as
 *   the backend resolved it — a `CONFIRMED` entry's own real headline/effect is shown as-is; a
 *   `LINE_RELEVANT` entry's [DisruptionPresentation.uncertainLineDesignations] is populated so the
 *   notification/widget's own conservative "Line X disruption" presentation applies instead of the
 *   real classified effect (see that field's own doc).
 * - More than one genuinely different entry falls back to the generic [DisruptionEffect.DISRUPTION]
 *   label — there is no upstream-provided way to say which of several distinct disruptions matters
 *   most, and a confidently-wrong "most important" pick would be worse than the honest generic one
 *   (matches this function's own pre-existing behavior for multiple distinct Journey Planner
 *   notices). If EVERY one of those distinct entries is itself only [DisruptionRelevance.LINE_RELEVANT],
 *   the fallback stays conservatively line-scoped (the union of every matched line designation,
 *   letting the notification/widget builder decide between naming a small number of lines or
 *   falling back to a fully generic wording) rather than silently upgrading to a plain "Disruption"
 *   label that no longer says anything about which lines are involved. A MIX of `CONFIRMED` and
 *   `LINE_RELEVANT` entries (or multiple `CONFIRMED` ones) uses the plain generic
 *   [DisruptionEffect.DISRUPTION] label with no line-designation involvement, exactly like two
 *   distinct `CONFIRMED` entries always have.
 *
 * Distinctness is re-checked here too (by [ResolvedJourneyDisruption.id] when available, else
 * [ResolvedJourneyDisruption.headline] — mirroring the backend's own dedup precedence), not merely
 * assumed from the caller — this function's own contract must hold regardless of whether [this]
 * happens to already be deduplicated. Routine Details shows every distinct entry individually (see
 * `RoutineDetailsScreen`'s own disruption cards) — only this single compact value collapses them
 * for the notification/widget's one-line indicator. `null` when [this] is empty — nothing to show.
 */
fun List<ResolvedJourneyDisruption>.compactPresentation(): DisruptionPresentation? {
    val distinct = distinctBy { it.id ?: it.headline }
    val first = distinct.firstOrNull() ?: return null
    if (distinct.size == 1) return first.toPresentation()

    val allLineRelevant = distinct.all { it.relevance == DisruptionRelevance.LINE_RELEVANT }
    val uncertainLineDesignations = if (allLineRelevant) distinct.flatMap { it.matchedLineDesignations }.distinct() else emptyList()
    return DisruptionPresentation(
        headline = first.headline,
        details = null,
        effect = DisruptionEffect.DISRUPTION,
        uncertainLineDesignations = uncertainLineDesignations,
    )
}

/**
 * Conservative single-value aggregation of PRIMARY's own Journey Planner notices ALONE — used
 * only for the worker's FIRST, primary-only post, BEFORE this tick's deviations-relevance lookup
 * has resolved (or if it never resolves at all — see
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own two-phase primary-first/
 * deviations-second posting). Every entry here is a Journey Planner notice attached directly to
 * PRIMARY — the strongest possible evidence (see [DisruptionRelevance.CONFIRMED]'s own doc) — so
 * [DisruptionPresentation.uncertainLineDesignations] is always empty from this function; the
 * [compactPresentation] overload above is what the SECOND, deviations-aware post uses instead,
 * and is the only one that can ever populate that field.
 *
 * Named distinctly from [compactPresentation] rather than overloaded under the same name: Kotlin
 * extension functions differing only by a generic type argument (`List<JourneyDisruptionNotice>`
 * vs `List<ResolvedJourneyDisruption>`) erase to the identical JVM signature and collide
 * ("Platform declaration clash") — true overloading is not possible here.
 *
 * Same aggregation rule as [compactPresentation]: a single distinct notice's own real
 * text/details/effect is shown as-is; more than one genuinely different notice falls back to the
 * generic [DisruptionEffect.DISRUPTION] label with the first occurrence's own real headline text
 * and no details body — never an invented "most important" ranking. Distinctness is by exact
 * [JourneyDisruptionNotice.text] (defensive — [primaryDisruptionNotices] already deduplicates the
 * same way; this function's own contract must hold regardless of whether [this] happens to
 * already be deduplicated).
 */
fun List<JourneyDisruptionNotice>.compactJourneyPlannerPresentation(): DisruptionPresentation? {
    val distinct = distinctBy { it.text }
    val first = distinct.firstOrNull() ?: return null
    if (distinct.size == 1) return DisruptionPresentation(first.text, first.details, first.effect)
    return DisruptionPresentation(first.text, null, DisruptionEffect.DISRUPTION)
}
