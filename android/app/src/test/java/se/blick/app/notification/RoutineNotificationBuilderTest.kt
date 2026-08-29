package se.blick.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.blick.app.MainActivity
import se.blick.app.R
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.ui.screens.routinedetails.formatDepartureTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Robolectric-backed tests asserting on the REAL constructed [Notification] and, for
 * [AndroidRoutineNotifier], the real framework [NotificationManager] — not a second
 * hand-rolled copy of the expected configuration. See `libs.versions.toml`'s `robolectric`
 * entry for why this targets `@Config(sdk = [34])` rather than this project's actual
 * `compileSdk`/`targetSdk` of 36, and why `application = android.app.Application::class` is
 * used (this class constructs [RoutineNotificationBuilder]/[AndroidRoutineNotifier] directly
 * with a plain [Context]; no Hilt component needs to spin up).
 *
 * Kept as its own test class, separate from `RoutineNotificationMapperTest` (a plain JVM
 * suite with no Android/Robolectric dependency at all) — see this milestone's Part 7
 * requirement that the two layers stay in separate test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoutineNotificationBuilderTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val builder = RoutineNotificationBuilder(context)

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private fun model(
        routineId: String = "r1",
        stationName: String = "Fruängen",
        lineLabel: String? = "14",
        directionLabel: String? = "Fruängen",
        content: RoutineNotificationContent = RoutineNotificationContent.Live(listOf(sampleRow())),
        disruptionHeadline: String? = null,
        disruptionDetails: String? = null,
        // Real callers (RoutineNotificationMapper) only ever set headline and effect together --
        // Disruption.effect defaults to DISRUPTION upstream, never null. Defaulting effect off of
        // headline here keeps that same pairing for every existing call site below that only
        // passes disruptionHeadline, without having to touch each one individually.
        disruptionEffect: DisruptionEffect? = if (disruptionHeadline != null) DisruptionEffect.DISRUPTION else null,
        disruptionUncertainLineDesignations: List<String> = emptyList(),
    ) = RoutineNotificationModel(
        routineId,
        stationName,
        lineLabel,
        directionLabel,
        content,
        disruptionHeadline,
        disruptionDetails,
        disruptionEffect,
        disruptionUncertainLineDesignations,
    )

    private fun sampleRow(
        lineDesignation: String = "14",
        destinationLabel: String? = "Fruängen",
        effectiveTime: Instant = now.plusSeconds(240),
        minutesRemaining: Long = 4,
        isRealTime: Boolean = true,
        isCancelled: Boolean = false,
        journeyRole: JourneyRole? = null,
    ) = NotificationDepartureRow(lineDesignation, destinationLabel, effectiveTime, minutesRemaining, isRealTime, isCancelled, journeyRole)

    private fun exactModel(
        exact: ExactDestinationNotificationPresentation,
        stationName: String = "Slussen",
        directionLabel: String = "Kungsträdgården",
    ) = model(
        stationName = stationName,
        lineLabel = "19",
        directionLabel = directionLabel,
        content = RoutineNotificationContent.Live(listOf(sampleRow())),
    ).copy(
        routineType = RoutineType.EXACT_DESTINATION,
        exactDestination = exact,
    )

    private fun exactPresentation(
        legs: List<NotificationTransitLeg> = listOf(
            NotificationTransitLeg(TransportMode.METRO, "19", "Hässelby strand"),
            NotificationTransitLeg(TransportMode.METRO, "11", "Kungsträdgården"),
        ),
        changes: Int = 1,
        primaryMinutes: Long = 4,
        nextMinutes: Long? = 12,
    ) = ExactDestinationNotificationPresentation(
        primaryCountdownMinutes = primaryMinutes,
        transitLegs = legs,
        arrivalTime = Instant.parse("2026-08-23T09:02:00Z"),
        primaryChangeCount = changes,
        nextCountdownMinutes = nextMinutes,
    )

    // ---- Channel ----

    @Test
    fun `building creates the expected channel with quiet importance`() {
        builder.build(model())
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID)
        assertEquals(RoutineNotificationIds.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `channel creation is idempotent across repeated builds`() {
        builder.build(model())
        builder.build(model())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.notificationChannels.size)
    }

    // ---- Core flags ----

    @Test
    fun `the built notification uses the dedicated small icon`() {
        val notification = builder.build(model())
        assertEquals(R.drawable.ic_stat_blick, notification.smallIcon.resId)
    }

    @Test
    fun `the built notification is ongoing, only-alert-once, not auto-cancel, and publicly visible`() {
        val notification = builder.build(model())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL == 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
    }

    // ---- Content intent (Part 5: tap navigation) ----

    @Test
    fun `the content PendingIntent targets MainActivity with the model's routine id`() {
        val notification = builder.build(model(routineId = "r-42"))
        val savedIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        assertEquals("ROUTINE", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_TYPE))
        assertEquals("r-42", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_ID))
    }

    @Test
    fun `rebuilding for a different routine id updates the content intent's extras`() {
        builder.build(model(routineId = "r1"))
        val second = builder.build(model(routineId = "r2"))
        val savedIntent = shadowOf(second.contentIntent).savedIntent
        assertEquals("r2", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_ID))
    }

    // ---- Stop action ("Stop/Unpin" control) ----

    @Test
    fun `the built notification has exactly one Stop action targeting StopRoutineNotificationReceiver with the model's routine id`() {
        val notification = builder.build(model(routineId = "r-42"))
        assertEquals(1, notification.actions.size)
        val action = notification.actions.single()
        assertEquals(context.getString(R.string.notification_action_stop), action.title.toString())
        val savedIntent = shadowOf(action.actionIntent).savedIntent
        assertEquals(StopRoutineNotificationReceiver::class.java.name, savedIntent.component?.className)
        assertEquals("r-42", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `rebuilding for a different routine id updates the Stop action's extras`() {
        builder.build(model(routineId = "r1"))
        val second = builder.build(model(routineId = "r2"))
        val savedIntent = shadowOf(second.actions.single().actionIntent).savedIntent
        assertEquals("r2", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    // ---- Title: the one place route/line/destination is shown ----

    private fun title(notification: Notification): String = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun `title combines the pinned line, station, and destination in one line`() {
        val notification = builder.build(model(stationName = "Slussen", lineLabel = "14", directionLabel = "Fruängen"))
        assertEquals(
            context.getString(R.string.notification_title_format, "14", "Slussen", "Fruängen"),
            title(notification),
        )
    }

    @Test
    fun `exact title uses only the saved origin and final destination`() {
        val notification = builder.build(exactModel(exactPresentation()))

        assertEquals(
            context.getString(R.string.notification_exact_route_title_format, "Slussen", "Kungsträdgården"),
            title(notification),
        )
        assertFalse(title(notification).contains("19"))
        assertFalse(title(notification).contains("Hässelby strand"))
    }

    @Test
    fun `missing pinned line and direction use fallback wording in the title`() {
        val notification = builder.build(model(lineLabel = null, directionLabel = null))
        assertTrue(title(notification).contains(context.getString(R.string.notification_line_fallback)))
        assertTrue(title(notification).contains(context.getString(R.string.notification_direction_fallback)))
    }

    // ---- Title is identical regardless of content state (Fix 4's original intent) ----

    @Test
    fun `the title carries the same line, station, and destination in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        val expected = context.getString(R.string.notification_title_format, "14", "Fruängen", "Fruängen")
        states.forEach { content ->
            val notification = builder.build(model(lineLabel = "14", stationName = "Fruängen", directionLabel = "Fruängen", content = content))
            assertEquals("expected the same title for $content", expected, title(notification))
        }
    }

    @Test
    fun `missing line falls back to the line-fallback wording in the title, in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        states.forEach { content ->
            val notification = builder.build(model(lineLabel = null, content = content))
            assertTrue(
                "expected line fallback in title for $content",
                title(notification).contains(context.getString(R.string.notification_line_fallback)),
            )
        }
    }

    @Test
    fun `missing direction falls back to the direction-fallback wording in the title, in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        states.forEach { content ->
            val notification = builder.build(model(directionLabel = null, content = content))
            assertTrue(
                "expected direction fallback in title for $content",
                title(notification).contains(context.getString(R.string.notification_direction_fallback)),
            )
        }
    }

    @Test
    fun `the collapsed and expanded body never repeat the line designation or destination shown in the title`() {
        val notification = builder.build(
            model(
                lineLabel = "14",
                directionLabel = "Fruängen",
                content = RoutineNotificationContent.Live(listOf(sampleRow(lineDesignation = "14", destinationLabel = "Fruängen"))),
            ),
        )
        val collapsed = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val expanded = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertFalse(collapsed.contains("Fruängen"))
        assertFalse(expanded.contains("Fruängen"))
    }

    // ---- Expanded departure lines ----
    //
    // NotificationCompat.BigTextStyle (Notification.EXTRA_BIG_TEXT), not InboxStyle
    // (Notification.EXTRA_TEXT_LINES) -- see RoutineNotificationBuilder's own class doc on
    // why: Android 16's promoted-ongoing ("Live Update") surface only allows a handful of
    // styles, and InboxStyle is not one of them. The up-to-two rows are joined by "\n" into
    // one wrapped text block instead of separate bulleted lines.

    private fun bigTextLines(notification: Notification): List<String> =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split("\n")

    /** Null when no [androidx.core.app.NotificationCompat.BigTextStyle] was applied at all —
     * unlike [bigTextLines], safe to call for states that may or may not have one (e.g.
     * Offline/Unavailable/Loading only gain one when a disruption is present). */
    private fun bigTextOrNull(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private fun contentText(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `exact expanded notification separates countdown boarding transfer final arrival changes and NEXT`() {
        val exact = exactPresentation()
        val notification = builder.build(exactModel(exact))
        val arrival = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(exact.arrivalTime)

        assertEquals(
            listOf(
                "⏰ 4 min",
                "Metro 19 toward Hässelby strand",
                "Change to Metro 11 toward Kungsträdgården",
                context.resources.getQuantityString(R.plurals.notification_exact_arrival_with_changes, 1, arrival, 1),
                context.getString(R.string.notification_next_departure_format, "12 min"),
            ),
            bigTextLines(notification),
        )
        assertTrue(bigTextLines(notification).none { it.isBlank() })
        assertEquals(0x23F0, bigTextLines(notification).first().codePointAt(0))
    }

    @Test
    fun `exact countdowns at an hour or more use localized hours and remaining minutes`() {
        val notification = builder.build(
            exactModel(exactPresentation(primaryMinutes = 1_317, nextMinutes = 122)),
        )

        assertEquals("⏰ 21 hr 57 min", bigTextLines(notification).first())
        assertEquals("Next in 2 hr 2 min", bigTextLines(notification).last())
        assertEquals("21 hr 57 min · Metro 19\nNext in 2 hr 2 min", contentText(notification))
    }

    @Test
    fun `exact countdown switches from minutes to hours at sixty minutes`() {
        val notification = builder.build(
            exactModel(exactPresentation(primaryMinutes = 60, nextMinutes = 59)),
        )

        assertEquals("⏰ 1 hr", bigTextLines(notification).first())
        assertEquals("Next in 59 min", bigTextLines(notification).last())
    }

    @Test
    fun `exact collapsed notification keeps countdown and first boarding line without using headsign as title`() {
        val notification = builder.build(exactModel(exactPresentation()))

        assertEquals("4 min · Metro 19\nNext in 12 min", contentText(notification))
        assertEquals("Slussen → Kungsträdgården", title(notification))
    }

    @Test
    fun `exact direct notification has no transfer row no zero-changes text and no NEXT placeholder`() {
        val exact = exactPresentation(
            legs = listOf(NotificationTransitLeg(TransportMode.METRO, "13", "Ropsten")),
            changes = 0,
            nextMinutes = null,
        )
        val notification = builder.build(exactModel(exact, directionLabel = "T-Centralen"))
        val lines = bigTextLines(notification)

        assertEquals(3, lines.size)
        assertEquals("Metro 13 toward Ropsten", lines[1])
        assertTrue(lines[2].startsWith("Arrive "))
        assertFalse(lines.joinToString().contains("change"))
        assertFalse(lines.joinToString().contains("Next"))
    }

    @Test
    fun `exact expanded notification renders every transfer in itinerary order`() {
        val exact = exactPresentation(
            legs = listOf(
                NotificationTransitLeg(TransportMode.METRO, "11", "Kungsträdgården"),
                NotificationTransitLeg(TransportMode.METRO, "17", "Skarpnäck"),
                NotificationTransitLeg(TransportMode.BUS, "4", "Gullmarsplan"),
            ),
            changes = 2,
        )

        val lines = bigTextLines(builder.build(exactModel(exact)))

        assertEquals("Metro 11 toward Kungsträdgården", lines[1])
        assertEquals("Change to Metro 17 toward Skarpnäck", lines[2])
        assertEquals("Change to Bus 4 toward Gullmarsplan", lines[3])
    }

    @Test
    fun `exact notification uses Swedish boarding transfer arrival and NEXT wording`() {
        val swedishContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("sv")))
            },
        )
        val notification = RoutineNotificationBuilder(swedishContext).build(exactModel(exactPresentation()))
        val lines = bigTextLines(notification)

        assertEquals("Tunnelbana 19 mot Hässelby strand", lines[1])
        assertEquals("Byt till Tunnelbana 11 mot Kungsträdgården", lines[2])
        assertTrue(lines[3].startsWith("Ankomst "))
        assertEquals("Nästa om 12 min", lines[4])
    }

    @Test
    fun `exact long countdown uses Swedish localized hours and minutes`() {
        val swedishContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("sv")))
            },
        )
        val notification = RoutineNotificationBuilder(swedishContext).build(
            exactModel(exactPresentation(primaryMinutes = 1_317, nextMinutes = 122)),
        )

        assertEquals("⏰ 21 tim 57 min", bigTextLines(notification).first())
        assertEquals("Nästa om 2 tim 2 min", bigTextLines(notification).last())
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS")
    fun `exact arrival remains 24-hour time even when the locale normally uses AM PM`() {
        val notification = builder.build(exactModel(exactPresentation()))
        val arrivalLine = bigTextLines(notification).first { it.startsWith("Arrive ") }

        assertTrue(arrivalLine.matches(Regex("Arrive \\d{2}:\\d{2} · 1 change")))
        assertFalse(arrivalLine.contains("AM"))
        assertFalse(arrivalLine.contains("PM"))
    }

    @Test
    fun `malformed exact Live model cannot fall back to generic Live status text`() {
        val notification = builder.build(
            model(
                stationName = "Slussen",
                directionLabel = "Kungsträdgården",
                content = RoutineNotificationContent.Live(listOf(sampleRow(minutesRemaining = 3))),
            ).copy(routineType = RoutineType.EXACT_DESTINATION, exactDestination = null),
        )

        assertEquals(context.getString(R.string.notification_unavailable), contentText(notification))
        assertFalse(contentText(notification).contains("Live"))
    }

    @Test
    fun `the primary departure line shows its countdown and Live status`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(minutesRemaining = 3, isRealTime = true)))),
        )
        assertEquals(
            listOf(context.getString(R.string.notification_departure_status_format, "3 min", context.getString(R.string.routine_details_departure_live))),
            bigTextLines(notification),
        )
    }

    @Test
    fun `the primary departure line shows Scheduled status for a non-real-time departure`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(minutesRemaining = 18, isRealTime = false)))),
        )
        assertEquals(
            listOf(context.getString(R.string.notification_departure_status_format, "18 min", context.getString(R.string.routine_details_departure_scheduled))),
            bigTextLines(notification),
        )
    }

    @Test
    fun `a second departure adds a Next line with its own countdown`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(minutesRemaining = 3, isRealTime = true), sampleRow(minutesRemaining = 18, isRealTime = false)),
                ),
            ),
        )
        val lines = bigTextLines(notification)
        assertEquals(2, lines.size)
        assertEquals(context.getString(R.string.notification_next_departure_format, "18 min"), lines[1])
    }

    @Test
    fun `no following departure omits the Next line entirely`() {
        val notification = builder.build(model(content = RoutineNotificationContent.Live(listOf(sampleRow()))))
        assertEquals(1, bigTextLines(notification).size)
    }

    @Test
    fun `a cancelled primary departure shows Cancelled alone, with no countdown`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(isCancelled = true, minutesRemaining = 4, isRealTime = true)))),
        )
        val line = bigTextLines(notification).single()
        assertEquals(context.getString(R.string.routine_details_departure_cancelled), line)
    }

    @Test
    fun `a cancelled following departure shows a distinct Next Cancelled line`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(), sampleRow(isCancelled = true, minutesRemaining = 18)),
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_next_departure_cancelled), bigTextLines(notification)[1])
    }

    // ---- Second-row wording depends on JourneyRole (exact-destination only) ----

    @Test
    fun `a NEXT-role second departure keeps the existing Next wording`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(), sampleRow(minutesRemaining = 18, journeyRole = JourneyRole.NEXT)),
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_next_departure_format, "18 min"), bigTextLines(notification)[1])
    }

    @Test
    fun `an ALTERNATIVE-role second departure visibly says Alternative, not Next`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(), sampleRow(minutesRemaining = 18, journeyRole = JourneyRole.ALTERNATIVE)),
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_alternative_departure_format, "18 min"), bigTextLines(notification)[1])
    }

    @Test
    fun `a row carrying ALTERNATIVE because it was restored from a stale snapshot still renders Alternative, not Next`() {
        // NotificationDepartureRow.journeyRole doesn't know or care whether it came from a
        // live fetch or a restored StaleSnapshotMappers.kt round-trip -- the whole point of
        // persisting the role there is that this rendering decision needs no special case
        // for "stale" at all. See RoomStaleSnapshotRepositoryTest's own round-trip tests for
        // the persistence half of this guarantee.
        val restoredFromStaleSnapshot = sampleRow(minutesRemaining = 18, journeyRole = JourneyRole.ALTERNATIVE)
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Stale(
                    departures = listOf(sampleRow(), restoredFromStaleSnapshot),
                    lastCheckedAt = now,
                ),
            ),
        )
        // expandedLines: [staleWarning, primaryLine, secondLine, lastCheckedLine] -- see
        // applyContent's own Stale branch.
        assertEquals(context.getString(R.string.notification_alternative_departure_format, "18 min"), bigTextLines(notification)[2])
    }

    @Test
    fun `a cancelled ALTERNATIVE second departure shows a distinct Alternative Cancelled line, not Next Cancelled`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(), sampleRow(isCancelled = true, minutesRemaining = 18, journeyRole = JourneyRole.ALTERNATIVE)),
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_alternative_departure_cancelled), bigTextLines(notification)[1])
    }

    @Test
    fun `a second departure with no role at all (the ordinary LINE_DIRECTION path) keeps the existing Next wording, unchanged`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(), sampleRow(minutesRemaining = 18, journeyRole = null)),
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_next_departure_format, "18 min"), bigTextLines(notification)[1])
    }

    // ---- Distinct state content ----

    @Test
    fun `Stale content states that refresh failed in the collapsed text`() {
        val lastCheckedAt = now.minusSeconds(600)
        val notification = builder.build(
            model(content = RoutineNotificationContent.Stale(listOf(sampleRow()), lastCheckedAt)),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(context.getString(R.string.notification_stale_warning), text)
    }

    @Test
    fun `Stale's expanded view includes the last-known departure and a last-checked time`() {
        val lastCheckedAt = now.minusSeconds(600)
        val notification = builder.build(
            model(content = RoutineNotificationContent.Stale(listOf(sampleRow(minutesRemaining = 6)), lastCheckedAt)),
        )
        val lines = bigTextLines(notification)
        assertTrue(lines.any { it.contains("6") })
        // No explicit app locale is set anywhere in this test class -- see the dedicated
        // app-locale-vs-system-locale coverage below for the case where one is -- so this
        // matches RoutineNotificationBuilder's own fallback: whatever locale this SAME context
        // already resolves resources with, not necessarily Locale.getDefault() (see
        // se.blick.app.locale.withAppLocale's own doc on why those two are not always the same).
        val expectedTimeText = formatDepartureTime(lastCheckedAt, context.resources.configuration.locales[0])
        assertTrue(lines.any { it.contains(expectedTimeText) })
    }

    // ---- Blick's own selected app locale, not the device/system one (see
    // se.blick.app.locale.withAppLocale's own doc) ----
    //
    // No test here calls AppCompatDelegate.setApplicationLocales() directly and expects
    // builder.build() to reflect it: withAppLocale() now reads the explicit choice via
    // LocaleManagerCompat.getApplicationLocales(context) (a background-context-safe read, fixing
    // the bug where a WorkManager-started process with no Activity ever created could not see a
    // previously-persisted explicit choice) rather than AppCompatDelegate's own
    // getApplicationLocales(), whose backing storage on API 32 and below is only ever hydrated
    // from an Activity/delegate's own lifecycle. Calling setApplicationLocales() alone, with no
    // AppCompatActivity anywhere in this test process, updates only that in-memory field and
    // never reaches LocaleManagerCompat's view -- confirmed directly from AppCompatDelegateImpl's
    // own source (see AppLocaleTest's own test of this exact property) and empirically (a
    // version of this test using exactly that pattern started failing the moment withAppLocale()
    // switched to LocaleManagerCompat, which is the expected, correct consequence of the fix,
    // not a regression). Explicit-choice propagation through withAppLocale() is already covered
    // once, at its source, in AppLocaleTest -- RoutineNotificationBuilder calls that same
    // function unchanged, so re-deriving the same guarantee here would be redundant coverage,
    // not missing coverage. The one scenario genuinely out of reach of this unit-test
    // infrastructure -- a previously-persisted explicit choice surviving an actual process death,
    // read back by a process that never creates MainActivity -- needs a real-device check; see
    // this milestone's own final report for that acknowledgment.

    // ---- No explicit Blick choice, unsupported system locale (Lithuanian) -- withAppLocale()
    // must resolve this the same way ordinary resource fallback already does, not leave
    // locale-sensitive formatting (the last-checked clock time) on the raw, unsupported system
    // locale (see se.blick.app.locale.effectiveBlickLocale's own doc). No explicit
    // setApplicationLocales call anywhere in this test -- this is specifically the
    // no-explicit-choice case. ----

    @Test
    @Config(sdk = [26], qualifiers = "lt")
    fun `no explicit Blick choice on an unsupported Lithuanian system locale resolves English notification text and 24-hour last-checked time`() {
        // Sanity check that the simulated device/system locale really is the unsupported one.
        assertEquals("lt", context.resources.configuration.locales[0].language)

        val loadingNotification = builder.build(model(content = RoutineNotificationContent.Loading))
        assertEquals(
            "Updating departures…",
            loadingNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        val lastCheckedAt = now.minusSeconds(600)
        val staleNotification = builder.build(
            model(content = RoutineNotificationContent.Stale(listOf(sampleRow(minutesRemaining = 6)), lastCheckedAt)),
        )
        val englishTimeText = formatDepartureTime(lastCheckedAt, Locale.forLanguageTag("en"))
        val lines = bigTextLines(staleNotification)
        assertTrue("expected the English-formatted last-checked time in: $lines", lines.any { it.contains(englishTimeText) })
        assertFalse(englishTimeText.contains("AM"))
        assertFalse(englishTimeText.contains("PM"))
    }

    @Test
    fun `no explicit Blick choice, ordered system locale list Lithuanian-then-Swedish, resolves Swedish notification text`() {
        // Config.qualifiers cannot express a multi-entry ordered locale list (Robolectric's
        // qualifier-string parser only accepts a single locale) -- this builds the Configuration
        // directly via the same public android.os.LocaleList/Context.createConfigurationContext
        // APIs Android itself uses, independent of any AndroidX/AppCompat internals, and
        // constructs a second RoutineNotificationBuilder around it (this class's own `builder`
        // stays on the plain, single-locale `context` every other test relies on).
        val multiLocaleContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("lt"), Locale.forLanguageTag("sv")))
            },
        )
        val multiLocaleBuilder = RoutineNotificationBuilder(multiLocaleContext)

        val notification = multiLocaleBuilder.build(model(content = RoutineNotificationContent.Loading))

        assertEquals(
            "Uppdaterar avgångar…",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `no explicit Blick choice, ordered system locale list Lithuanian-then-Swedish, resolves a Swedish classified disruption summary line`() {
        val multiLocaleContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("lt"), Locale.forLanguageTag("sv")))
            },
        )
        val multiLocaleBuilder = RoutineNotificationBuilder(multiLocaleContext)

        val notification = multiLocaleBuilder.build(
            model(disruptionHeadline = "Försenat", disruptionEffect = DisruptionEffect.DELAYS),
        )

        assertEquals(
            "⚠️ Förseningar · Tryck för detaljer",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().split("\n").last(),
        )
    }

    // ---- Disruption content: never the real text anywhere, only a classified summary line ----
    //
    // See RoutineNotificationBuilder's own class doc: verified directly on a real Android 16
    // device that a promoted-ongoing notification's row has no expand_button at all, so
    // whatever BigTextStyle contains is unconditionally shown -- there is no reliable
    // collapsed state to hide a disruption's real header/details behind. Both the collapsed
    // contentText AND the "expanded" bigText therefore only ever carry a short classified
    // summary line (e.g. "⚠️ Delays · Tap for details", derived from the model's own
    // disruptionEffect -- see backend/src/normalize/classifyDisruptionEffect.ts for how that
    // effect is derived); the real message is read by tapping into Routine Details instead.
    // shortCriticalText and the Stop action are still never touched by it.

    /** Mirrors RoutineNotificationBuilder's own private disruptionEffectLabel() mapping, so
     * tests can assert on the exact rendered line without duplicating string-resource lookups
     * inline. The "each classified effect renders its own exact summary line in English" test
     * below additionally pins every effect to a hardcoded literal, so a mistake shared between
     * this mapping and the production one would still be caught. */
    private fun disruptionText(effect: DisruptionEffect): String {
        val label = context.getString(
            when (effect) {
                DisruptionEffect.DELAYS -> R.string.notification_disruption_effect_delays
                DisruptionEffect.NO_SERVICE -> R.string.notification_disruption_effect_no_service
                DisruptionEffect.REDUCED_SERVICE -> R.string.notification_disruption_effect_reduced_service
                DisruptionEffect.ROUTE_CHANGE -> R.string.notification_disruption_effect_route_change
                DisruptionEffect.STOP_CHANGE -> R.string.notification_disruption_effect_stop_change
                DisruptionEffect.REPLACEMENT_SERVICE -> R.string.notification_disruption_effect_replacement_service
                DisruptionEffect.STATION_ACCESS -> R.string.notification_disruption_effect_station_access
                DisruptionEffect.ACCESSIBILITY_ISSUE -> R.string.notification_disruption_effect_accessibility_issue
                DisruptionEffect.DISRUPTION -> R.string.notification_disruption_effect_disruption
            },
        )
        return context.getString(R.string.notification_disruption_format, label)
    }

    @Test
    fun `no disruption adds no expanded-view line beyond Live's own departure rows`() {
        val notification = builder.build(model(content = RoutineNotificationContent.Live(listOf(sampleRow()))))
        assertEquals(1, bigTextLines(notification).size)
    }

    @Test
    fun `each classified effect renders its own exact summary line in English`() {
        val cases = mapOf(
            DisruptionEffect.DELAYS to "⚠️ Delays · Tap for details",
            DisruptionEffect.NO_SERVICE to "⚠️ No service · Tap for details",
            DisruptionEffect.REDUCED_SERVICE to "⚠️ Reduced service · Tap for details",
            DisruptionEffect.ROUTE_CHANGE to "⚠️ Route change · Tap for details",
            DisruptionEffect.STOP_CHANGE to "⚠️ Stop change · Tap for details",
            DisruptionEffect.REPLACEMENT_SERVICE to "⚠️ Replacement service · Tap for details",
            DisruptionEffect.STATION_ACCESS to "⚠️ Station access · Tap for details",
            DisruptionEffect.ACCESSIBILITY_ISSUE to "⚠️ Accessibility issue · Tap for details",
            DisruptionEffect.DISRUPTION to "⚠️ Disruption · Tap for details",
        )
        cases.forEach { (effect, expected) ->
            val notification = builder.build(model(disruptionHeadline = "SL message", disruptionEffect = effect))
            assertEquals("expected $effect to render as: $expected", expected, bigTextLines(notification).last())
        }
    }

    @Test
    fun `a disruption adds only its classified summary line after Live's departure rows, never the real headline or details`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(listOf(sampleRow())),
                disruptionHeadline = "Delays on line 14",
                disruptionDetails = "Expect longer travel times.",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        val lines = bigTextLines(notification)
        assertEquals(listOf(disruptionText(DisruptionEffect.DELAYS)), lines.drop(1))
    }

    @Test
    fun `a disruption adds its classified summary line after Stale's departure and last-checked lines`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Stale(listOf(sampleRow()), now),
                disruptionHeadline = "Delays on line 14",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        assertEquals(disruptionText(DisruptionEffect.DELAYS), bigTextLines(notification).last())
    }

    @Test
    fun `a disruption adds its classified summary line after NoUpcomingDepartures' last-checked line`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.NoUpcomingDepartures(now),
                disruptionHeadline = "Delays on line 14",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        assertEquals(disruptionText(DisruptionEffect.DELAYS), bigTextLines(notification).last())
    }

    @Test
    fun `Offline gains no expanded view at all without a disruption`() {
        val notification = builder.build(model(content = RoutineNotificationContent.Offline))
        assertNull(bigTextOrNull(notification))
    }

    @Test
    fun `a disruption gives Offline an expanded view with its offline message plus its classified summary line, never the real message`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Offline,
                disruptionHeadline = "Delays on line 14",
                disruptionDetails = "Details",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        assertEquals(
            listOf(context.getString(R.string.notification_offline), disruptionText(DisruptionEffect.DELAYS)),
            bigTextLines(notification),
        )
    }

    @Test
    fun `a disruption gives Unavailable an expanded view with its unavailable message plus its classified summary line, never the real message`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Unavailable,
                disruptionHeadline = "Delays on line 14",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        assertEquals(
            listOf(context.getString(R.string.notification_unavailable), disruptionText(DisruptionEffect.DELAYS)),
            bigTextLines(notification),
        )
    }

    @Test
    fun `a disruption appends its classified summary line to the collapsed contentText`() {
        val withoutDisruption = builder.build(model(content = RoutineNotificationContent.Live(listOf(sampleRow()))))
        val withDisruption = builder.build(
            model(
                content = RoutineNotificationContent.Live(listOf(sampleRow())),
                disruptionHeadline = "Delays on line 14",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        assertEquals(
            contentText(withoutDisruption) + "\n" + disruptionText(DisruptionEffect.DELAYS),
            contentText(withDisruption),
        )
    }

    @Test
    fun `neither the collapsed contentText nor the expanded bigText ever contains the disruption's own header or details`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(listOf(sampleRow())),
                disruptionHeadline = "Delays on line 14",
                disruptionDetails = "Expect longer travel times.",
            ),
        )
        assertFalse(contentText(notification).contains("Delays on line 14"))
        assertFalse(contentText(notification).contains("Expect longer travel times."))
        val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertFalse(bigText.contains("Delays on line 14"))
        assertFalse(bigText.contains("Expect longer travel times."))
    }

    @Test
    fun `the collapsed contentText keeps the departure information ahead of the disruption's classified summary line`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(listOf(sampleRow())),
                disruptionHeadline = "Delays on line 14",
                disruptionEffect = DisruptionEffect.DELAYS,
            ),
        )
        val text = contentText(notification)
        val indicator = disruptionText(DisruptionEffect.DELAYS)
        assertTrue("expected departure row to precede the indicator in: $text", text.indexOf(indicator) > 0)
    }

    @Test
    fun `states with no disruption never show a disruption summary line, in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        states.forEach { content ->
            val notification = builder.build(model(content = content))
            // "Tap for details" is the static suffix shared by every one of the 9 rendered
            // summary lines (see notification_disruption_format) -- a single substring check
            // catches all of them without listing each DisruptionEffect by hand.
            assertFalse("expected no summary line for $content", contentText(notification).contains("Tap for details"))
        }
    }

    @Test
    fun `a disruption adds its classified summary line to the collapsed text in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        val indicator = disruptionText(DisruptionEffect.DELAYS)
        states.forEach { content ->
            val notification = builder.build(
                model(content = content, disruptionHeadline = "Delays on line 14", disruptionEffect = DisruptionEffect.DELAYS),
            )
            assertTrue("expected the summary line for $content", contentText(notification).contains(indicator))
        }
    }

    @Test
    fun `a disruption never changes the title`() {
        val withoutDisruption = builder.build(model())
        val withDisruption = builder.build(model(disruptionHeadline = "Delays on line 14"))
        assertEquals(title(withoutDisruption), title(withDisruption))
    }

    @Test
    fun `a disruption never removes the Stop action`() {
        val notification = builder.build(model(disruptionHeadline = "Delays on line 14"))
        assertTrue(notification.actions.any { it.title == context.getString(R.string.notification_action_stop) })
    }

    // ---- LINE_RELEVANT: a conservative "Line X disruption" label, never the raw classified
    // effect claim -- see RoutineNotificationModel.disruptionUncertainLineDesignations' own doc
    // and the Akalla -> T-Centralen false-positive this exists to prevent (an SL Deviation whose
    // line/mode scope matched PRIMARY, but whose affected segment/stop was not proven to
    // intersect this exact journey, must never be shown as though "No service" were confirmed
    // for this rider's own trip). ----

    @Test
    fun `a single matched line with disruptionUncertainLineDesignations renders the conservative Line X disruption label, not the raw effect`() {
        val notification = builder.build(
            model(
                disruptionHeadline = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
                disruptionEffect = DisruptionEffect.NO_SERVICE,
                disruptionUncertainLineDesignations = listOf("11"),
            ),
        )
        val expected = context.getString(
            R.string.notification_disruption_format,
            context.getString(R.string.notification_disruption_line_relevant_single_format, "11"),
        )
        assertEquals(expected, bigTextLines(notification).last())
        // The raw classified effect's own label must never appear anywhere in the rendered text.
        assertFalse(contentText(notification).contains(context.getString(R.string.notification_disruption_effect_no_service)))
    }

    @Test
    fun `more than one matched line falls back to the generic Line disruption label, never a concatenated list`() {
        val notification = builder.build(
            model(
                disruptionHeadline = "Trafikstörning",
                disruptionEffect = DisruptionEffect.DISRUPTION,
                disruptionUncertainLineDesignations = listOf("11", "17"),
            ),
        )
        val expected = context.getString(
            R.string.notification_disruption_format,
            context.getString(R.string.notification_disruption_line_relevant_generic),
        )
        assertEquals(expected, bigTextLines(notification).last())
    }

    @Test
    fun `empty disruptionUncertainLineDesignations -- a CONFIRMED disruption -- still renders the real classified effect label unchanged`() {
        val notification = builder.build(
            model(
                disruptionHeadline = "Inställd trafik",
                disruptionEffect = DisruptionEffect.NO_SERVICE,
                disruptionUncertainLineDesignations = emptyList(),
            ),
        )
        assertEquals(disruptionText(DisruptionEffect.NO_SERVICE), bigTextLines(notification).last())
    }

    @Test
    fun `the conservative line-relevant label is also used in the collapsed contentText`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(listOf(sampleRow())),
                disruptionHeadline = "Inställd trafik",
                disruptionEffect = DisruptionEffect.NO_SERVICE,
                disruptionUncertainLineDesignations = listOf("11"),
            ),
        )
        val expected = context.getString(
            R.string.notification_disruption_format,
            context.getString(R.string.notification_disruption_line_relevant_single_format, "11"),
        )
        assertTrue(contentText(notification).endsWith(expected))
    }

    @Test
    fun `no explicit Blick choice, ordered system locale list Lithuanian-then-Swedish, resolves a Swedish conservative line-relevant label`() {
        val multiLocaleContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("lt"), Locale.forLanguageTag("sv")))
            },
        )
        val multiLocaleBuilder = RoutineNotificationBuilder(multiLocaleContext)

        val notification = multiLocaleBuilder.build(
            model(
                disruptionHeadline = "Inställd trafik",
                disruptionEffect = DisruptionEffect.NO_SERVICE,
                disruptionUncertainLineDesignations = listOf("11"),
            ),
        )

        assertEquals(
            "⚠️ Störning på linje 11 · Tryck för detaljer",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().split("\n").last(),
        )
    }

    // ---- Promoted-ongoing (Live Update) request ----

    @Test
    fun `every built notification requests promoted-ongoing status`() {
        val states = listOf(
            RoutineNotificationContent.Live(listOf(sampleRow())),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
        )
        states.forEach { content ->
            val notification = builder.build(model(content = content))
            assertTrue(
                "expected setRequestPromotedOngoing(true) for $content",
                androidx.core.app.NotificationCompat.isRequestPromotedOngoing(notification),
            )
        }
    }

    @Test
    fun `Live content sets the soonest departure's countdown as the short critical text`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(minutesRemaining = 4), sampleRow(minutesRemaining = 19)),
                ),
            ),
        )
        assertEquals(
            context.getString(R.string.routine_details_minutes_remaining, 4L),
            androidx.core.app.NotificationCompat.getShortCriticalText(notification),
        )
    }

    @Test
    fun `a cancelled soonest departure sets the cancelled label as the short critical text`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(isCancelled = true)))),
        )
        assertEquals(
            context.getString(R.string.routine_details_departure_cancelled),
            androidx.core.app.NotificationCompat.getShortCriticalText(notification),
        )
    }

    @Test
    fun `no short critical text is set when there is no departure to summarize`() {
        val notification = builder.build(model(content = RoutineNotificationContent.NoUpcomingDepartures(now)))
        assertEquals(null, androidx.core.app.NotificationCompat.getShortCriticalText(notification))
    }

    @Test
    fun `NoUpcomingDepartures content is distinct from Offline and Unavailable`() {
        val offlineText = builder.build(model(content = RoutineNotificationContent.Offline))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val unavailableText = builder.build(model(content = RoutineNotificationContent.Unavailable))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val noDeparturesText = builder.build(model(content = RoutineNotificationContent.NoUpcomingDepartures(now)))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        assertEquals(context.getString(R.string.notification_offline), offlineText)
        assertEquals(context.getString(R.string.notification_unavailable), unavailableText)
        assertEquals(context.getString(R.string.notification_no_departures), noDeparturesText)
        assertEquals(3, setOf(offlineText, unavailableText, noDeparturesText).size)
    }

    @Test
    fun `Loading content shows a quiet updating message`() {
        val notification = builder.build(model(content = RoutineNotificationContent.Loading))
        assertEquals(
            context.getString(R.string.notification_loading),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }
}

/**
 * Covers [AndroidRoutineNotifier]'s own responsibilities — posting/updating/cancelling a
 * single stable notification id via the real framework [NotificationManager] — separately
 * from [RoutineNotificationBuilder]'s construction concerns above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidRoutineNotifierTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val availabilityChecker = AndroidNotificationAvailabilityChecker(context)
    private val notifier = AndroidRoutineNotifier(context, RoutineNotificationBuilder(context), availabilityChecker)
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    // POST_NOTIFICATIONS (API 33+) is a dangerous permission -- declaring it in the manifest
    // does not grant it, matching real first-install device behavior, so Robolectric denies it
    // by default. Every test in this class is about the app-wide toggle/channel state, not the
    // runtime permission itself, so it's granted upfront here.
    @Before
    fun grantNotificationPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun model(stationName: String = "Fruängen") = RoutineNotificationModel(
        routineId = "r1",
        stationName = stationName,
        lineLabel = "14",
        directionLabel = "Fruängen",
        content = RoutineNotificationContent.NoUpcomingDepartures(now),
    )

    @Test
    fun `showOrUpdate posts under the stable notification id`() {
        notifier.showOrUpdate(model())
        val posted = manager.activeNotifications.singleOrNull { it.id == RoutineNotificationIds.NOTIFICATION_ID }
        assertTrue(posted != null)
    }

    @Test
    fun `repeated showOrUpdate calls update the same notification id rather than posting a new one`() {
        notifier.showOrUpdate(model(stationName = "Fruängen"))
        notifier.showOrUpdate(model(stationName = "Slussen"))

        assertEquals(1, manager.activeNotifications.size)
        val posted = manager.activeNotifications.single { it.id == RoutineNotificationIds.NOTIFICATION_ID }
        assertTrue(posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Slussen"))
    }

    @Test
    fun `remove cancels the posted notification`() {
        notifier.showOrUpdate(model())
        assertEquals(1, manager.activeNotifications.size)

        notifier.remove()

        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `remove without a prior post is a harmless no-op`() {
        notifier.remove()
        assertEquals(0, manager.activeNotifications.size)
    }

    // ---- NotificationPostResult (Fix 3) ----

    @Test
    fun `a successful post returns Posted`() {
        val result = notifier.showOrUpdate(model())
        assertEquals(NotificationPostResult.Posted, result)
    }

    @Test
    fun `disabled notifications return NotificationsDisabled and post nothing`() {
        shadowOf(manager).setNotificationsEnabled(false)

        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.NotificationsDisabled, result)
        assertEquals(0, manager.activeNotifications.size)
    }

    // ---- Disabled Blick channel specifically (distinct from the app-wide toggle above) ----

    @Test
    fun `app notifications enabled and the Blick channel enabled -- a post succeeds`() {
        // No channel exists yet in a fresh Robolectric environment; showOrUpdate's own
        // ensureChannel() creates it (at IMPORTANCE_LOW, i.e. enabled) as part of a normal post.
        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.Posted, result)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun `app notifications enabled but the Blick channel importance is IMPORTANCE_NONE -- NotificationsDisabled, nothing posted`() {
        // Simulates the user having disabled specifically the Blick channel (distinct from
        // disabling notifications for the whole app) via a REAL NotificationChannel at the
        // real framework NotificationManager -- not a hand-rolled duplicate of the notifier's
        // own logic.
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.NotificationsDisabled, result)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `a disabled Blick channel is reported as NotificationsDisabled, never as Failed`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        val result = notifier.showOrUpdate(model())

        assertTrue(result !is NotificationPostResult.Failed)
        assertEquals(NotificationPostResult.NotificationsDisabled, result)
    }

    @Test
    fun `a disabled Blick channel is never recreated or modified by showOrUpdate`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        notifier.showOrUpdate(model())

        val channelAfter = manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channelAfter.importance)
        assertEquals(1, manager.notificationChannels.size)
    }

    @Test
    fun `an unexpected construction failure returns Failed without throwing`() {
        val failingBuilder = mockk<RoutineNotificationBuilder>()
        every { failingBuilder.build(any()) } throws RuntimeException("boom")
        val notifierWithFailingBuilder = AndroidRoutineNotifier(context, failingBuilder, availabilityChecker)

        val result = notifierWithFailingBuilder.showOrUpdate(model())

        assertEquals(NotificationPostResult.Failed, result)
        assertEquals(0, manager.activeNotifications.size)
    }
}

/**
 * Covers [AndroidNotificationAvailabilityChecker] directly — the one shared source of truth
 * [AndroidRoutineNotifier], `RoutineActiveWindowWorker`, and the routine details screen all
 * read through (see [NotificationAvailability]'s class doc) — distinguishing all four states
 * against the real framework [NotificationManager] rather than a hand-rolled duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidNotificationAvailabilityCheckerTest {

    private val application: android.app.Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val checker = AndroidNotificationAvailabilityChecker(context)

    // See AndroidRoutineNotifierTest's identical @Before for why this is needed: only the two
    // tests explicitly about the runtime permission itself (below) should ever see it missing.
    @Before
    fun grantNotificationPermission() {
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `nothing blocking -- Available`() {
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `no channel yet and notifications enabled -- still Available`() {
        // A missing channel is not ChannelDisabled -- the normal channel-creation path must
        // still be allowed to run the first time a notification is actually built (see
        // NotificationAvailability.Available's own doc).
        assertEquals(null, manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID))
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `app-wide notifications disabled -- AppDisabled`() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationAvailability.AppDisabled, checker.check())
    }

    @Test
    fun `Blick channel specifically disabled -- ChannelDisabled`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )
        assertEquals(NotificationAvailability.ChannelDisabled, checker.check())
    }

    @Test
    fun `Blick channel exists and enabled -- Available`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_LOW),
        )
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `runtime permission missing on API 33+ -- PermissionMissing, checked before the app-wide toggle`() {
        shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Even with the app-wide toggle enabled, the missing runtime permission on API 33+
        // must be reported first -- it is the more specific, more actionable reason.
        assertEquals(NotificationAvailability.PermissionMissing, checker.check())
    }

    @Test
    fun `runtime permission granted on API 33+ and everything else fine -- Available`() {
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationAvailability.Available, checker.check())
    }
}
