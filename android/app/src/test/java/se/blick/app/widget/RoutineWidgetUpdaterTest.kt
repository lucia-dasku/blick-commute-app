package se.blick.app.widget

import android.content.Context
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.local.datastore.AppSettings
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.scheduling.DeviceZoneProvider
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tests [RoutineWidgetUpdater]'s own default implementation of the four-argument
 * [RoutineWidgetUpdater.updateWithDepartures] overload — added as a *new* overload (not an
 * added parameter on the existing three-argument method) specifically so implementations that
 * predate the widget's disruption strip (test fakes across several other files) don't need to
 * change at all: calling the four-argument overload on any implementation that only overrides
 * the three-argument one must silently forward to it, ignoring the disruption. Only
 * [GlanceRoutineWidgetUpdater] (exercised indirectly via `RoutineActiveWindowWorkerTest`'s own
 * `RecordingWidgetUpdater`, which DOES override both) actually renders a disruption.
 */
class RoutineWidgetUpdaterTest {

    private fun routine() = CommuteRoutine(
        id = "r1",
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = null,
        lineDesignation = "14",
        directionCode = null,
        destinationLabel = "Fruängen",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun disruption() = Disruption(
        disruptionId = "d1",
        version = 1,
        createdAt = Instant.EPOCH,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage("Delays on line 14", "Details", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    /** Implements ONLY the pre-existing three-argument method — exactly what every
     * `RoutineWidgetUpdater` fake elsewhere in this codebase looked like before the disruption
     * strip existed, and still looks like in every file that has no reason to care about it. */
    private class ThreeArgumentOnlyFake : RoutineWidgetUpdater {
        var lastRoutine: CommuteRoutine? = null
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            lastRoutine = routine
        }
        override suspend fun clear() = Unit
        override suspend fun reconcile() = Unit
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    @Test
    fun `calling the four-argument overload on a fake that only overrides three arguments still forwards, ignoring the disruption`() =
        runTest {
            val fake = ThreeArgumentOnlyFake()
            val routine = routine()

            fake.updateWithDepartures(routine, LiveDeparturesState.Loading, Instant.EPOCH, disruption())

            assertEquals(routine, fake.lastRoutine)
        }
}

class EffectiveWidgetThemeTest {

    @Test
    fun `explicit Light overrides a dark Samsung-style system configuration`() {
        val theme = resolveEffectiveWidgetTheme(
            settings = AppSettings(useDarkTheme = false),
            hasPremiumAccess = false,
            isSystemNightMode = true,
        )

        assertEquals(EffectiveWidgetTheme(useStockholmNightTheme = false, useDarkTheme = false), theme)
    }

    @Test
    fun `explicit Dark overrides a light Lenovo-style system configuration`() {
        val theme = resolveEffectiveWidgetTheme(
            settings = AppSettings(useDarkTheme = true),
            hasPremiumAccess = false,
            isSystemNightMode = false,
        )

        assertEquals(EffectiveWidgetTheme(useStockholmNightTheme = false, useDarkTheme = true), theme)
    }

    @Test
    fun `System remains the only mode that follows device night configuration`() {
        val settings = AppSettings(useDarkTheme = null)

        assertEquals(false, resolveEffectiveWidgetTheme(settings, false, isSystemNightMode = false).useDarkTheme)
        assertEquals(true, resolveEffectiveWidgetTheme(settings, false, isSystemNightMode = true).useDarkTheme)
    }

    @Test
    fun `Stockholm Night remains Premium-only and always dark`() {
        val settings = AppSettings(useDarkTheme = false, useStockholmNightTheme = true)

        assertEquals(
            EffectiveWidgetTheme(useStockholmNightTheme = true, useDarkTheme = true),
            resolveEffectiveWidgetTheme(settings, hasPremiumAccess = true, isSystemNightMode = false),
        )
        assertEquals(
            EffectiveWidgetTheme(useStockholmNightTheme = false, useDarkTheme = false),
            resolveEffectiveWidgetTheme(settings, hasPremiumAccess = false, isSystemNightMode = true),
        )
    }
}

/**
 * [GlanceRoutineWidgetUpdater.refreshPresentation] must never re-derive or change what an
 * active widget is showing -- see that method's own doc and [RoutineWidgetUpdater.refreshPresentation]'s
 * interface doc on why a language-only switch must not risk the same
 * [RoutineWidgetContent.Loading]/[RoutineWidgetUiState.NoActiveCommute] regression [reconcile]
 * can cause. Asserted here at the one point that's actually verifiable without a full
 * Glance/AppWidgetManager render pipeline (which no test in this codebase sets up for
 * [GlanceRoutineWidgetUpdater] specifically): [refreshPresentation] must never even READ
 * [RoutineRepository]/[NotificationAvailabilityChecker], which [reconcile] always does to
 * re-derive state -- if it never reads them, it cannot have used them to compute anything new.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GlanceRoutineWidgetUpdaterRefreshPresentationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val routineRepository = mockk<RoutineRepository>()
    private val notificationAvailabilityChecker = mockk<NotificationAvailabilityChecker>()
    private val appSettingsDataStore = mockk<AppSettingsDataStore>()
    private val deviceZoneProvider = DeviceZoneProvider { ZoneId.of("UTC") }
    private val updater = GlanceRoutineWidgetUpdater(
        context = context,
        routineRepository = routineRepository,
        notificationAvailabilityChecker = notificationAvailabilityChecker,
        clock = Clock.systemUTC(),
        deviceZoneProvider = deviceZoneProvider,
        appSettingsDataStore = appSettingsDataStore,
    )

    @Test
    fun `refreshPresentation never queries the routine repository or notification availability`() = runTest {
        updater.refreshPresentation(isSystemNightMode = true)

        coVerify(exactly = 0) { routineRepository.observeAll() }
        coVerify(exactly = 0) { notificationAvailabilityChecker.check() }
    }

    @Test
    fun `refreshPresentation with no placed widget instances completes without throwing`() = runTest {
        // No GlanceAppWidgetManager setup/placed instance anywhere in this test -- proves this
        // is safe to call unconditionally (e.g. right after a language switch) even when Blick
        // has no home-screen widget placed at all.
        updater.refreshPresentation()
    }
}
