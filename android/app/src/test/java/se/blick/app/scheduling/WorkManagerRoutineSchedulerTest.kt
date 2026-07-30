package se.blick.app.scheduling

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Uses WorkManager's own official test harness ([WorkManagerTestInitHelper] +
 * [SynchronousExecutor]) against a REAL (in-memory, synchronous) [WorkManager] instance rather
 * than a hand-rolled fake — so these tests assert on the same unique-work-name/
 * `ExistingWorkPolicy.REPLACE` behaviour [WorkManagerRoutineScheduler] actually relies on, not a
 * duplicated re-implementation of it.
 *
 * [clock] is deliberately fixed at [ZoneOffset.UTC] — a zone that DISAGREES with [zoneProvider]'s
 * `Europe/Stockholm` for every instant used below — so any regression back to reading
 * `clock`'s own zone (as this scheduler used to, before the device-local-timezone fix; see
 * [WorkManagerRoutineScheduler]'s class doc) would shift the computed activation time by a
 * whole UTC offset and be caught by the [WorkInfo.getNextScheduleTimeMillis] assertions below,
 * not silently pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WorkManagerRoutineSchedulerTest {

    // Monday 05:00 Europe/Stockholm (CEST, UTC+2) -- deliberately BEFORE the default routine()'s
    // 07:00-09:00 window, not exactly at its start: NextOccurrenceCalculator.nextOccurrence
    // treats "now == startTime" as NextOccurrence.ActiveNow (Duration.ZERO initialDelay), which
    // under this class's SynchronousExecutor test harness runs RoutineActiveWindowWorker
    // immediately and synchronously -- with no Hilt component in this plain Robolectric test,
    // that worker fails immediately, leaving WorkInfo in a non-ENQUEUED state and breaking every
    // `state == WorkInfo.State.ENQUEUED` assertion below.
    private val now: Instant = Instant.parse("2026-07-27T03:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zoneProvider = FakeDeviceZoneProvider(ZoneId.of("Europe/Stockholm"))

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerRoutineScheduler

    /** Settable fake so tests can simulate a live device timezone change between two
     * [WorkManagerRoutineScheduler.scheduleActivation] calls (see [DeviceZoneProvider]'s own
     * doc on why this is a method, not a cached value, in production too). */
    private class FakeDeviceZoneProvider(var zone: ZoneId) : DeviceZoneProvider {
        override fun currentZone(): ZoneId = zone
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
    }

    private fun routine(
        id: String = "r1",
        enabled: Boolean = true,
        activeDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        startTime: LocalTime = LocalTime.of(7, 0),
        endTime: LocalTime = LocalTime.of(9, 0),
        pausedDate: java.time.LocalDate? = null,
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = activeDays,
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
        pausedDate = pausedDate,
    )

    private fun workInfosFor(routineId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerRoutineScheduler.uniqueWorkName(routineId)).get()

    /**
     * [WorkInfo.getNextScheduleTimeMillis] is WorkManager's own real wall-clock enqueue time
     * plus the requested [androidx.work.WorkRequest.Builder.setInitialDelay] -- it has no idea
     * about [WorkManagerRoutineScheduler]'s injected (fake, fixed-in-the-past) [Clock], so it
     * can never equal an absolute instant computed from that fake clock. What IS verifiable is
     * the DELAY [WorkManagerRoutineScheduler] computed (fakeNow to the expected target) --
     * this asserts `actual` falls within [toleranceMs] of `realNowAroundTheCall + expectedDelay`,
     * bracketed by real timestamps taken immediately before/after the call to absorb ordinary
     * test-execution overhead.
     */
    private fun assertScheduledAfterDelay(
        realBeforeCallMs: Long,
        realAfterCallMs: Long,
        expectedDelay: Duration,
        actualNextScheduleTimeMillis: Long,
        toleranceMs: Long = 10_000,
    ) {
        val lowerBound = realBeforeCallMs + expectedDelay.toMillis() - toleranceMs
        val upperBound = realAfterCallMs + expectedDelay.toMillis() + toleranceMs
        assertTrue(
            "expected nextScheduleTimeMillis ($actualNextScheduleTimeMillis) within " +
                "[$lowerBound, $upperBound] (real enqueue time + ${expectedDelay.toMillis()}ms delay)",
            actualNextScheduleTimeMillis in lowerBound..upperBound,
        )
    }

    /** The negative-case counterpart to [assertScheduledAfterDelay] -- asserts `actual` is NOT
     * close to what a wrong-zone [expectedWrongDelay] would have produced, catching a regression
     * back to the wrong offset even though neither fake-clock-based delay can be compared to
     * `actual` by exact equality (see [assertScheduledAfterDelay]'s own doc). */
    private fun assertNotScheduledAfterDelay(
        realBeforeCallMs: Long,
        realAfterCallMs: Long,
        expectedWrongDelay: Duration,
        actualNextScheduleTimeMillis: Long,
        toleranceMs: Long = 10_000,
    ) {
        val lowerBound = realBeforeCallMs + expectedWrongDelay.toMillis() - toleranceMs
        val upperBound = realAfterCallMs + expectedWrongDelay.toMillis() + toleranceMs
        assertTrue(
            "expected nextScheduleTimeMillis ($actualNextScheduleTimeMillis) NOT within " +
                "[$lowerBound, $upperBound] (the wrong-zone interpretation)",
            actualNextScheduleTimeMillis !in lowerBound..upperBound,
        )
    }

    @Test
    fun `scheduling an enabled routine enqueues unique work under its routine id`() {
        scheduler.scheduleActivation(routine())
        val infos = workInfosFor("r1")

        assertEquals(1, infos.size)
        assertTrue(infos.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `scheduling a disabled routine does not enqueue any work`() {
        scheduler.scheduleActivation(routine(enabled = false))
        assertTrue(workInfosFor("r1").isEmpty())
    }

    @Test
    fun `scheduling a routine with no active days does not enqueue any work`() {
        scheduler.scheduleActivation(routine(activeDays = emptySet()))
        assertTrue(workInfosFor("r1").isEmpty())
    }

    @Test
    fun `re-scheduling the same routine replaces the previous work rather than adding a second one`() {
        scheduler.scheduleActivation(routine())
        scheduler.scheduleActivation(routine(startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0)))

        val infos = workInfosFor("r1")
        // Exactly one non-cancelled entry for this unique work name -- REPLACE means the
        // previous request is torn down, not left enqueued alongside the new one.
        assertEquals(1, infos.count { it.state != WorkInfo.State.CANCELLED })
    }

    @Test
    fun `disabling a previously-scheduled routine cancels its pending work`() {
        scheduler.scheduleActivation(routine())
        assertEquals(1, workInfosFor("r1").count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.scheduleActivation(routine(enabled = false))

        assertTrue(workInfosFor("r1").none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `cancelActivation cancels any pending work for that routine id`() {
        scheduler.scheduleActivation(routine())
        assertEquals(1, workInfosFor("r1").count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.cancelActivation("r1")

        assertTrue(workInfosFor("r1").none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `cancelActivation for a routine with no scheduled work is a harmless no-op`() {
        scheduler.cancelActivation("never-scheduled")
        assertTrue(workInfosFor("never-scheduled").isEmpty())
    }

    @Test
    fun `two different routines are scheduled independently under distinct unique work names`() {
        scheduler.scheduleActivation(routine(id = "r1"))
        scheduler.scheduleActivation(routine(id = "r2"))

        assertEquals(1, workInfosFor("r1").size)
        assertEquals(1, workInfosFor("r2").size)

        scheduler.cancelActivation("r1")

        assertTrue(workInfosFor("r1").none { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, workInfosFor("r2").count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `pausing today (excludedDate) still schedules work, for the next eligible day`() {
        // routine() is only active on Monday; "now" is itself that Monday before its window,
        // so excluding today must push the scheduled work to the following Monday rather than
        // leaving nothing scheduled. "today" is computed via the DEVICE zone (zoneProvider),
        // matching what the scheduler itself now uses internally -- not via clock.zone, which
        // is deliberately a different (UTC) zone in this test class (see class doc).
        val today = now.atZone(zoneProvider.currentZone()).toLocalDate()
        scheduler.scheduleActivation(routine(pausedDate = today))

        assertEquals(1, workInfosFor("r1").count { it.state == WorkInfo.State.ENQUEUED })
    }

    // ---- Device-local timezone (Fix 1) ----

    @Test
    fun `a Stockholm routine activates at 07-30 Stockholm time in summer (CEST, UTC+2), not 07-30 UTC`() {
        // Monday 2026-07-27, 06:00 Stockholm (CEST) -- one and a half hours before the
        // routine's 07:30 start, still the same calendar day in both UTC and Stockholm so the
        // "which weekday is today" part of the calculation can't mask a wrong-zone bug; only
        // the actual computed activation instant can.
        val summerNow = Instant.parse("2026-07-27T04:00:00Z")
        val summerClock = Clock.fixed(summerNow, ZoneOffset.UTC)
        val summerScheduler = WorkManagerRoutineScheduler(RuntimeEnvironment.getApplication(), summerClock, zoneProvider)

        val realBefore = System.currentTimeMillis()
        summerScheduler.scheduleActivation(routine(startTime = LocalTime.of(7, 30), endTime = LocalTime.of(9, 0)))
        val realAfter = System.currentTimeMillis()

        val expectedStockholm0730 = Instant.parse("2026-07-27T05:30:00Z") // 07:30 CEST == 05:30 UTC
        val wrongUtcInterpretation = Instant.parse("2026-07-27T07:30:00Z") // what 07:30 UTC would be instead
        val actual = workInfosFor("r1").single().nextScheduleTimeMillis

        assertScheduledAfterDelay(realBefore, realAfter, Duration.between(summerNow, expectedStockholm0730), actual)
        assertNotScheduledAfterDelay(realBefore, realAfter, Duration.between(summerNow, wrongUtcInterpretation), actual)
    }

    @Test
    fun `a Stockholm routine activates at 07-30 Stockholm time in winter (CET, UTC+1), not 07-30 UTC`() {
        // Monday 2026-01-05, 05:00 Stockholm (CET) -- winter, a different UTC offset (+1) than
        // the summer case above, proving the zone resolution isn't hardcoded to one offset.
        val winterNow = Instant.parse("2026-01-05T03:00:00Z")
        val winterClock = Clock.fixed(winterNow, ZoneOffset.UTC)
        val winterScheduler = WorkManagerRoutineScheduler(RuntimeEnvironment.getApplication(), winterClock, zoneProvider)

        val realBefore = System.currentTimeMillis()
        winterScheduler.scheduleActivation(routine(startTime = LocalTime.of(7, 30), endTime = LocalTime.of(9, 0)))
        val realAfter = System.currentTimeMillis()

        val expectedStockholm0730 = Instant.parse("2026-01-05T06:30:00Z") // 07:30 CET == 06:30 UTC
        val wrongUtcInterpretation = Instant.parse("2026-01-05T07:30:00Z")
        val actual = workInfosFor("r1").single().nextScheduleTimeMillis

        assertScheduledAfterDelay(realBefore, realAfter, Duration.between(winterNow, expectedStockholm0730), actual)
        assertNotScheduledAfterDelay(realBefore, realAfter, Duration.between(winterNow, wrongUtcInterpretation), actual)
    }

    @Test
    fun `a device timezone change recalculates the next window on the very next scheduleActivation call`() {
        // Same routine, same instant, scheduled once under Europe/Stockholm and then again
        // after the device zone "changes" to a different, fixed +05:00 offset -- the second
        // call must recompute against the NEW zone, not silently keep using the first one.
        val zoneProvider = FakeDeviceZoneProvider(ZoneId.of("Europe/Stockholm"))
        val fixedNow = Instant.parse("2026-07-27T04:00:00Z")
        val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val zoneChangingScheduler = WorkManagerRoutineScheduler(RuntimeEnvironment.getApplication(), fixedClock, zoneProvider)
        val testRoutine = routine(startTime = LocalTime.of(7, 30), endTime = LocalTime.of(9, 0))

        val realBeforeStockholm = System.currentTimeMillis()
        zoneChangingScheduler.scheduleActivation(testRoutine)
        val realAfterStockholm = System.currentTimeMillis()
        val underStockholm = workInfosFor("r1").single().nextScheduleTimeMillis
        assertScheduledAfterDelay(
            realBeforeStockholm,
            realAfterStockholm,
            Duration.between(fixedNow, Instant.parse("2026-07-27T05:30:00Z")),
            underStockholm,
        )

        zoneProvider.zone = ZoneOffset.ofHours(5)
        val realBeforeNewZone = System.currentTimeMillis()
        zoneChangingScheduler.scheduleActivation(testRoutine)
        val realAfterNewZone = System.currentTimeMillis()
        val underNewZone = workInfosFor("r1").single().nextScheduleTimeMillis

        // Under +5, fixedNow (04:00 UTC) is 09:00 local -- exactly candidateEnd for TODAY's
        // 07:30-09:00 window, and NextOccurrenceCalculator's boundary check requires
        // `now.isBefore(candidateEnd)` (strict), so `now == candidateEnd` means today's window
        // has already fully elapsed, not ActiveNow -- this correctly rolls over to the following
        // Monday (2026-08-03), not the same day. 07:30 local at UTC+5 == 02:30 UTC.
        assertScheduledAfterDelay(
            realBeforeNewZone,
            realAfterNewZone,
            Duration.between(fixedNow, Instant.parse("2026-08-03T02:30:00Z")),
            underNewZone,
        )
        assertNotEquals(underStockholm, underNewZone)
    }
}
