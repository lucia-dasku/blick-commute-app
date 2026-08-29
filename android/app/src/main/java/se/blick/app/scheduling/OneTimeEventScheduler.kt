package se.blick.app.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import se.blick.app.MainActivity
import se.blick.app.R
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.locale.withAppLocale
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

interface OneTimeEventScheduler {
    fun schedule(event: OneTimeEvent)
    fun cancel(eventId: String)
}

/** Schedules one best-effort event-day reminder. It never starts a refresh loop. */
@Singleton
class WorkManagerOneTimeEventScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) : OneTimeEventScheduler {
    override fun schedule(event: OneTimeEvent) {
        val workManager = WorkManager.getInstance(context)
        val reminderAt = event.date.atStartOfDay(STOCKHOLM_ZONE).toInstant()
        if (event.targetInstant() <= clock.instant()) {
            cancel(event.id)
            return
        }
        val delay = Duration.between(clock.instant(), reminderAt).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<OneTimeEventReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(OneTimeEventReminderWorker.KEY_EVENT_ID to event.id))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(event.id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(eventId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(eventId))
        NotificationManagerCompat.from(context).cancel(notificationId(eventId))
    }

    companion object {
        const val WORK_TAG = "one-time-event-reminder"
        private const val WORK_PREFIX = "one-time-event-reminder-"
        fun uniqueWorkName(eventId: String) = "$WORK_PREFIX$eventId"
        fun notificationId(eventId: String) = 40_000 + (eventId.hashCode() and 0x0FFF)
    }
}

@HiltWorker
class OneTimeEventReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: OneTimeEventRepository,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!entitlementRepository.entitlement.value.hasPremiumAccess) return Result.success()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()
        val event = repository.getById(eventId) ?: return Result.success()
        if (event.targetInstant() <= clock.instant()) return Result.success()

        val localizedContext = applicationContext.withAppLocale()
        createChannel(localizedContext)
        val notification = buildOneTimeEventReminderNotification(localizedContext, event)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(localizedContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        try {
            NotificationManagerCompat.from(localizedContext)
                .notify(WorkManagerOneTimeEventScheduler.notificationId(event.id), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and notify(). The event stays
            // persisted; a missing reminder must not turn into an endless WorkManager retry.
            return Result.success()
        }
        return Result.success()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.one_time_event_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val KEY_EVENT_ID = "oneTimeEventId"
        const val CHANNEL_ID = "blick_one_time_events"
    }
}

object OneTimeEventReminderNavigation {
    const val EXTRA_ONE_TIME_EVENT_ID = "se.blick.app.extra.ONE_TIME_EVENT_ID"

    fun putEventId(intent: Intent, eventId: String): Intent =
        intent.putExtra(EXTRA_ONE_TIME_EVENT_ID, eventId)

    fun consumeEventId(intent: Intent): String? =
        intent.getStringExtra(EXTRA_ONE_TIME_EVENT_ID)
            ?.also { intent.removeExtra(EXTRA_ONE_TIME_EVENT_ID) }
}

internal fun buildOneTimeEventReminderNotification(context: Context, event: OneTimeEvent) =
    NotificationCompat.Builder(context, OneTimeEventReminderWorker.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_blick)
        .setContentTitle(context.getString(R.string.one_time_event_today_notification_title, event.name))
        .setContentText(context.getString(R.string.one_time_event_today_notification_body))
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                WorkManagerOneTimeEventScheduler.notificationId(event.id),
                OneTimeEventReminderNavigation.putEventId(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    event.id,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .build()
