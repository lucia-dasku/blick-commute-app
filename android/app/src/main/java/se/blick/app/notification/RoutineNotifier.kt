package se.blick.app.notification

import se.blick.app.domain.model.DeparturesResult

/**
 * Represents the "one ongoing, updating notification" requirement from the product doc
 * (never a new notification per refresh). Deliberately an interface only in this
 * scaffold — no NotificationCompat/NotificationChannel implementation exists yet; that
 * is follow-up work once the scaffold is approved. See also scheduling/RoutineScheduler.kt,
 * which decides *when* this gets called.
 *
 * `minSdk = 26` is a product/support-coverage decision (see android/README.md), not a
 * hard technical requirement — notification channels are only meaningful on API 26+, so
 * a real implementation would create them conditionally guarded by
 * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` if a lower minSdk were chosen later.
 */
interface RoutineNotifier {
    fun showOrUpdate(routineId: String, departures: DeparturesResult)
    fun remove(routineId: String)
}
