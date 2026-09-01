package se.blick.app.widget

import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.AndroidResourceImageProvider
import androidx.glance.BackgroundModifier
import androidx.glance.EmittableImage
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.glance.unit.ColorProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.domain.model.TransportMode
import se.blick.app.notification.disruptionEffectLabelRes
import se.blick.app.ui.theme.StockholmNightSurfaces
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders [BlickWidgetContent] — the exact composable [BlickRoutineWidget.provideGlance] calls —
 * through Glance's own real unit-test rendering pipeline ([runGlanceAppWidgetUnitTest]), so these
 * prove what the widget actually draws, not a re-implementation of [resolveEffectiveModel]'s own
 * selection rules or [JourneyMainContent]'s own layout-selection logic. [BlickRoutineWidgetTest]
 * already covers [resolveEffectiveModel] and [legBadgesOrFallback] as pure functions in isolation;
 * this file is the complementary proof that [ActiveRoutineContent] (via [BlickWidgetContent]) truly
 * calls them and that the header badge, body, and [RoutineWidgetContent.Journeys.changesPreference]
 * all agree on what actually renders.
 *
 * The standard 3x2 size is used throughout so the secondary row is never dropped for size, only
 * for genuinely having expired or been
 * promoted — otherwise a compact-mode false negative could be mistaken for this bug being fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BlickRoutineWidgetRenderTest {

    private val context = RuntimeEnvironment.getApplication()
    // A fixed instant, passed explicitly to BlickWidgetContent's own `now` parameter below (see
    // that function's own doc) -- BlickWidgetContent no longer defaults it internally once a
    // caller supplies one, and ActiveRoutineContent no longer reads java.time.Instant.now() of
    // its own at all, so there is nothing left for this fixture to race against. (Previously this
    // was Instant.now(), relying on this read and ActiveRoutineContent's own separate internal
    // read landing close enough together in real time -- a genuine, if narrow, source of test
    // flakiness that threading `now` as an explicit parameter removes entirely.)
    private val now = Instant.parse("2026-08-11T08:00:00Z")
    private val arrivalFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private fun journeyRow(
        departureTime: Instant,
        lineDesignation: String,
        transportMode: TransportMode = TransportMode.BUS,
        role: JourneyRole = JourneyRole.PRIMARY,
        transferCount: Int = 0,
        legBadges: List<WidgetJourneyLegBadge> = emptyList(),
    ) = WidgetJourneyRow(
        lineDesignation = lineDesignation,
        transportMode = transportMode,
        departureTime = departureTime,
        arrivalTime = departureTime.plusSeconds(600),
        transferCount = transferCount,
        isRealtime = true,
        role = role,
        legBadges = legBadges,
    )

    private fun activeRoutineState(
        primary: WidgetJourneyRow,
        secondary: WidgetJourneyRow?,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
        disruptionHeadline: String? = null,
        disruptionUncertainLineDesignations: List<String> = emptyList(),
        disruptionEffect: DisruptionEffect? = null,
        label: RoutineLabel? = null,
        stationName: String = "Fruangen",
        directionLabel: String = "Arlanda",
    ) = RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routineId = "r1",
            routineName = "Airport commute",
            stationName = stationName,
            directionLabel = directionLabel,
            content = RoutineWidgetContent.Journeys(primary, secondary, changesPreference),
            lineDesignation = primary.lineDesignation,
            transportMode = primary.transportMode,
            disruptionHeadline = disruptionHeadline,
            disruptionUncertainLineDesignations = disruptionUncertainLineDesignations,
            disruptionEffect = disruptionEffect,
            label = label,
        ),
    )

    private fun countdownText(minutes: Long) = when {
        minutes < 60 -> context.getString(R.string.journey_duration_minutes, minutes)
        minutes % 60 == 0L -> context.getString(R.string.journey_duration_hours, minutes / 60)
        else -> context.getString(R.string.journey_duration_hours_minutes, minutes / 60, minutes % 60)
    }
    private fun directText() = context.getString(R.string.journey_direct)
    private fun withChangesText() = context.getString(R.string.journey_with_changes)
    private fun nextLabelText() = context.getString(R.string.widget_journey_next_label)
    private fun alternativeLabelText() = context.getString(R.string.widget_journey_alternative_label)
    private fun changesText(changes: Int) = context.resources.getQuantityString(R.plurals.widget_journey_changes, changes, changes)
    private fun arriveWithChangesText(arrival: Instant, changes: Int) = context.resources.getQuantityString(
        R.plurals.widget_journey_arrive_with_changes, changes, arrivalFormatter.format(arrival), changes,
    )
    private fun lineRelevantSingleText(line: String) = context.getString(R.string.notification_disruption_line_relevant_single_format, line)
    private fun lineRelevantGenericText() = context.getString(R.string.notification_disruption_line_relevant_generic)
    private fun effectText(effect: DisruptionEffect) = context.getString(disruptionEffectLabelRes(effect))

    private fun hasBackgroundColor(color: Color) = GlanceNodeMatcher<MappedNode>(
        "has background color $color",
    ) { node ->
        node.value.emittable.modifier.any { modifier ->
            modifier is BackgroundModifier.Color && modifier.colorProvider == ColorProvider(color)
        }
    }

    private fun hasResolvedBackgroundColor(color: Color, colorContext: android.content.Context) =
        GlanceNodeMatcher<MappedNode>("has resolved background color $color") { node ->
            node.value.emittable.modifier.any { modifier ->
                modifier is BackgroundModifier.Color && modifier.colorProvider.getColor(colorContext) == color
            }
        }

    private fun hasImageResource(resourceId: Int) = GlanceNodeMatcher<MappedNode>(
        "has image resource $resourceId",
    ) { node ->
        val image = node.value.emittable as? EmittableImage
        (image?.provider as? AndroidResourceImageProvider)?.resId == resourceId
    }

    private fun contextWithNightMode(isNightMode: Boolean) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (isNightMode) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        },
    )

    @Test
    fun `widget countdown changes from minutes to localized hours at sixty minutes`() {
        assertEquals("59 min", formatWidgetCountdown(context, 59))
        assertEquals("1 hr", formatWidgetCountdown(context, 60))
        assertEquals("2 hr 2 min", formatWidgetCountdown(context, 122))
        assertEquals("21 hr 57 min", formatWidgetCountdown(context, 1_317))
    }

    @Test
    fun `widget long countdown uses Swedish localized hours and minutes`() {
        val swedishContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList(Locale.forLanguageTag("sv")))
            },
        )

        assertEquals("21 tim 57 min", formatWidgetCountdown(swedishContext, 1_317))
        assertEquals("2 tim 2 min", formatWidgetCountdown(swedishContext, 122))
    }

    @Test
    fun `normal active widget uses Blick off-white background in system light mode`() =
        runGlanceAppWidgetUnitTest {
            val lightContext = contextWithNightMode(isNightMode = false)
            setContext(lightContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")

            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasBackgroundColor(Color(0xFFF3F5F4))).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertDoesNotExist()
        }

    @Test
    fun `normal active widget keeps Glance widget background in system dark mode`() =
        runGlanceAppWidgetUnitTest {
            val darkContext = contextWithNightMode(isNightMode = true)
            val existingDarkBackground = Color(
                darkContext.getColor(androidx.glance.R.color.glance_colorWidgetBackground),
            )
            setContext(darkContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")

            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasResolvedBackgroundColor(existingDarkBackground, darkContext)).assertExists()
            onNode(hasBackgroundColor(Color(0xFFF3F5F4))).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertDoesNotExist()
        }

    @Test
    fun `Stockholm Night active widget keeps its card and border surfaces without skyline artwork`() =
        runGlanceAppWidgetUnitTest {
            val lightContext = contextWithNightMode(isNightMode = false)
            setContext(lightContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")

            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(primary, null),
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasBackgroundColor(StockholmNightSurfaces.CardBorder)).assertExists()
            onNode(hasBackgroundColor(StockholmNightSurfaces.Card)).assertExists()
            onNode(hasBackgroundColor(Color(0xFFF3F5F4))).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertDoesNotExist()
        }

    // ---- Branded inactive state: real Glance composition at each responsive shape. ----

    @Test
    fun `compact light inactive widget renders its logo without status or brand title`() =
        runGlanceAppWidgetUnitTest {
            val lightContext = contextWithNightMode(isNightMode = false)
            setContext(lightContext)
            setAppWidgetSize(DpSize(110.dp, 80.dp))
            provideComposable { BlickWidgetContent(RoutineWidgetUiState.NoActiveCommute, now) }

            onNode(hasTextEqualTo(context.getString(R.string.app_name))).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_no_active_commute))).assertDoesNotExist()
        }

    @Test
    fun `standard inactive widget uses supplied artwork in system light mode`() =
        runGlanceAppWidgetUnitTest {
            val lightContext = contextWithNightMode(isNightMode = false)
            setContext(lightContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            provideComposable { BlickWidgetContent(RoutineWidgetUiState.NoActiveCommute, now) }

            onNode(hasBackgroundColor(Color(0xFFFAF4F3))).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_skyline_approved)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.app_name))).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_no_active_commute))).assertDoesNotExist()
        }

    @Test
    fun `standard inactive widget retains branded navy treatment in system dark mode`() =
        runGlanceAppWidgetUnitTest {
            val darkContext = contextWithNightMode(isNightMode = true)
            setContext(darkContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            provideComposable { BlickWidgetContent(RoutineWidgetUiState.NoActiveCommute, now) }

            onNode(hasBackgroundColor(Color(0xFF010C2F))).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_skyline_approved)).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_no_active_commute))).assertExists()
        }

    @Test
    fun `Stockholm Night inactive widget uses supplied background in system light mode`() =
        runGlanceAppWidgetUnitTest {
            val lightContext = contextWithNightMode(isNightMode = false)
            setContext(lightContext)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            provideComposable {
                BlickWidgetContent(
                    RoutineWidgetUiState.NoActiveCommute,
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasBackgroundColor(Color(0xFF010C2F))).assertExists()
            onNode(hasImageResource(R.drawable.widget_inactive_skyline_approved)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_inactive_light_background)).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.widget_stockholm_night_background)).assertExists()
            onNode(hasTextEqualTo(context.getString(R.string.widget_no_active_commute))).assertDoesNotExist()
        }

    @Test
    fun `large dark inactive widget renders its logo and status without a brand title`() =
        runGlanceAppWidgetUnitTest {
            val darkContext = contextWithNightMode(isNightMode = true)
            setContext(darkContext)
            setAppWidgetSize(DpSize(340.dp, 260.dp))
            provideComposable { BlickWidgetContent(RoutineWidgetUiState.NoActiveCommute, now) }

            onNode(hasTextEqualTo(context.getString(R.string.app_name))).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_no_active_commute))).assertExists()
        }

    @Test
    fun `inactive status resources omit punctuation in English and Swedish`() {
        fun localizedContext(language: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        )

        assertEquals("No active commute", localizedContext("en").getString(R.string.widget_no_active_commute))
        assertEquals("Ingen aktiv pendling", localizedContext("sv").getString(R.string.widget_no_active_commute))
    }

    // ---- Canonical launcher sizes: the real Glance tree exposes the exact fields assigned to
    // each supported tier. Pure rule tests separately pin the small route to two lines. ----

    @Test
    fun `Stockholm 2x2 shows label route dominant countdown badge and Direct without secondary details`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(180.dp, 130.dp))
            val primary = journeyRow(now.plusSeconds(240), lineDesignation = "13", transportMode = TransportMode.METRO)
            val next = journeyRow(now.plusSeconds(780), lineDesignation = "13", role = JourneyRole.NEXT)
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        next,
                        label = RoutineLabel.HOME,
                        stationName = "Slussen",
                        directionLabel = "Skanstull",
                    ),
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasTextEqualTo("Home")).assertExists()
            onNode(hasTextEqualTo("Slussen → Skanstull")).assertExists()
            onNode(hasTextEqualTo(countdownText(4))).assertExists()
            onNode(hasTextEqualTo("13")).assertExists()
            onNode(hasText(directText())).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_depart_label))).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_arrive_label))).assertDoesNotExist()
        }

    @Test
    fun `2x2 keeps a long two-stop route in the render tree without awkward manual truncation`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(180.dp, 130.dp))
            val primary = journeyRow(now.plusSeconds(240), lineDesignation = "13", transportMode = TransportMode.METRO)
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        null,
                        stationName = "T-Centralen",
                        directionLabel = "Malarhojden",
                    ),
                    now,
                )
            }

            onNode(hasTextEqualTo("T-Centralen → Malarhojden")).assertExists()
            onNode(hasTextEqualTo(countdownText(4))).assertExists()
        }

    @Test
    fun `Stockholm 3x2 shows label route composition divider and Next but omits journey times`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            val primary = journeyRow(now.plusSeconds(180), lineDesignation = "13", transportMode = TransportMode.METRO)
            val next = journeyRow(now.plusSeconds(780), lineDesignation = "13", role = JourneyRole.NEXT)
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(primary, next, label = RoutineLabel.WORK),
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasTextEqualTo("Work")).assertExists()
            onNode(hasTextEqualTo(countdownText(3))).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            onNode(hasTextEqualTo(countdownText(13))).assertExists()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_depart_label))).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_arrive_label))).assertDoesNotExist()
        }

    @Test
    fun `Stockholm 4x4 shows the approved route strip, journey times, and Alternative`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 280.dp))
            val primary = journeyRow(
                now.plusSeconds(180),
                lineDesignation = "129",
                transportMode = TransportMode.BUS,
                transferCount = 2,
                legBadges = listOf(
                    WidgetJourneyLegBadge("129", TransportMode.BUS, "Släggbacken, Stockholm"),
                    WidgetJourneyLegBadge("10", TransportMode.METRO, "Huvudsta centrum, Solna kommun"),
                    WidgetJourneyLegBadge("19", TransportMode.METRO, "T-Centralen, Stockholm, Stockholms län"),
                ),
            )
            val alternative = journeyRow(now.plusSeconds(780), lineDesignation = "57", role = JourneyRole.ALTERNATIVE)
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        alternative,
                        label = RoutineLabel.STUDY,
                        stationName = "Släggbacken",
                        directionLabel = "Globen",
                    ),
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasTextEqualTo(context.getString(R.string.routine_label_study))).assertExists()
            onNode(hasTextEqualTo("Släggbacken → Globen")).assertExists()
            onNode(hasTextEqualTo(countdownText(3))).assertExists()
            onNode(hasTextEqualTo(changesText(2))).assertExists()
            onNode(hasTextEqualTo("129")).assertExists()
            onNode(hasTextEqualTo("10")).assertExists()
            onNode(hasTextEqualTo("19")).assertExists()
            onAllNodes(hasTextEqualTo("••••••••••••••••")).assertCountEquals(2)
            onNode(hasTextEqualTo("Släggbacken")).assertExists()
            onNode(hasTextEqualTo("Huvudsta centrum")).assertExists()
            onNode(hasTextEqualTo("T-Centralen")).assertExists()
            onNode(hasTextEqualTo("Släggbacken, Stockholm")).assertDoesNotExist()
            onNode(hasTextEqualTo("Huvudsta centrum, Solna kommun")).assertDoesNotExist()
            onNode(hasTextEqualTo("T-Centralen, Stockholm, Stockholms län")).assertDoesNotExist()
            onNode(hasTextEqualTo("Bus")).assertDoesNotExist()
            onNode(hasTextEqualTo("Metro")).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_depart_label))).assertExists()
            onNode(hasTextEqualTo(arrivalFormatter.format(primary.departureTime))).assertExists()
            onNode(hasTextEqualTo(context.getString(R.string.widget_journey_arrive_label))).assertExists()
            onNode(hasTextEqualTo(arrivalFormatter.format(primary.arrivalTime))).assertExists()
            onNode(hasTextEqualTo("${alternativeLabelText()} ${countdownText(13)}  ›")).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
        }

    @Test
    fun `Stockholm 4x4 visibly renders a NEXT journey below the second divider`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 280.dp))
            val primary = journeyRow(
                now.plusSeconds(720),
                lineDesignation = "13",
                transportMode = TransportMode.METRO,
                transferCount = 1,
                legBadges = listOf(
                    WidgetJourneyLegBadge("13", TransportMode.METRO, "Mälarhöjden"),
                    WidgetJourneyLegBadge("41", TransportMode.TRAIN, "Stockholms södra"),
                ),
            )
            val next = journeyRow(
                now.plusSeconds(1_320),
                lineDesignation = "13",
                transportMode = TransportMode.METRO,
                role = JourneyRole.NEXT,
            )

            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary = primary,
                        secondary = next,
                        stationName = "Mälarhöjden",
                        directionLabel = "Tumba",
                    ),
                    now,
                    useStockholmNightTheme = true,
                )
            }

            onNode(hasTextEqualTo("${nextLabelText()} ${countdownText(22)}  ›")).assertExists()
        }

    @Test
    fun `3x2 keeps the existing composition and does not render Large route-strip station names`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(260.dp, 150.dp))
            val primary = journeyRow(
                now.plusSeconds(300),
                lineDesignation = "14",
                transportMode = TransportMode.METRO,
                transferCount = 1,
                legBadges = listOf(
                    WidgetJourneyLegBadge("14", TransportMode.METRO, "Fruängen"),
                    WidgetJourneyLegBadge("40", TransportMode.BUS, "Slussen"),
                ),
            )
            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasTextEqualTo("14")).assertExists()
            onNode(hasTextEqualTo("40")).assertExists()
            onNode(hasTextEqualTo("Fruängen")).assertDoesNotExist()
            onNode(hasTextEqualTo("Slussen")).assertDoesNotExist()
        }

    @Test
    fun `4x4 without a label or disruption leaves both optional areas absent`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 280.dp))
            val primary = journeyRow(now.plusSeconds(180), lineDesignation = "13")
            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasTextEqualTo("Home")).assertDoesNotExist()
            onNode(hasTextEqualTo(context.getString(R.string.routine_label_work))).assertDoesNotExist()
            onNode(hasText(lineRelevantGenericText())).assertDoesNotExist()
        }

    @Test
    fun `4x4 direct journey omits the redundant boarding station caption`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 280.dp))
            val primary = journeyRow(
                departureTime = now.plusSeconds(180),
                lineDesignation = "13",
                transportMode = TransportMode.METRO,
                transferCount = 0,
                legBadges = listOf(
                    WidgetJourneyLegBadge("13", TransportMode.METRO, "Mälarhöjden, Stockholm"),
                ),
            )

            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary = primary,
                        secondary = null,
                        stationName = "Mälarhöjden",
                        directionLabel = "Tumba",
                    ),
                    now,
                )
            }

            onNode(hasTextEqualTo("13")).assertExists()
            onNode(hasTextEqualTo(directText())).assertExists()
            onNode(hasTextEqualTo("Mälarhöjden")).assertDoesNotExist()
            onNode(hasTextEqualTo("Mälarhöjden, Stockholm")).assertDoesNotExist()
        }

    @Test
    fun `4x4 renders the classified Delays category and never the raw disruption headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 300.dp))
            val primary = journeyRow(now.plusSeconds(180), lineDesignation = "13")
            val rawHeadline = "Reduced service between Slussen and Skanstull"
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        null,
                        disruptionHeadline = rawHeadline,
                        disruptionEffect = DisruptionEffect.DELAYS,
                    ),
                    now,
                )
            }

            val delaysLabel = effectText(DisruptionEffect.DELAYS)
            assertEquals("Delays", delaysLabel)
            onNode(hasText(delaysLabel)).assertExists()
            onNode(hasTextEqualTo(rawHeadline)).assertDoesNotExist()
        }

    @Test
    @Config(qualifiers = "sv")
    fun `4x4 renders the Swedish classified Delays category and never the raw Swedish headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 300.dp))
            val primary = journeyRow(now.plusSeconds(180), lineDesignation = "13")
            val rawHeadline = "Försenad avgång mot Alvik"
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        null,
                        disruptionHeadline = rawHeadline,
                        disruptionEffect = DisruptionEffect.DELAYS,
                    ),
                    now,
                )
            }

            val delaysLabel = effectText(DisruptionEffect.DELAYS)
            assertEquals("Förseningar", delaysLabel)
            onNode(hasText(delaysLabel)).assertExists()
            onNode(hasTextEqualTo(rawHeadline)).assertDoesNotExist()
        }

    @Test
    fun `4x4 LINE_RELEVANT renders the conservative line label and never the effect or raw headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 300.dp))
            val primary = journeyRow(now.plusSeconds(180), lineDesignation = "11")
            val rawHeadline = "No service between T-Centralen and Kungstradgarden"
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary,
                        null,
                        disruptionHeadline = rawHeadline,
                        disruptionUncertainLineDesignations = listOf("11"),
                        disruptionEffect = DisruptionEffect.NO_SERVICE,
                    ),
                    now,
                )
            }

            onNode(hasText(lineRelevantSingleText("11"))).assertExists()
            onNode(hasTextEqualTo(rawHeadline)).assertDoesNotExist()
            onNode(hasText(effectText(DisruptionEffect.NO_SERVICE))).assertDoesNotExist()
        }

    @Test
    @Config(qualifiers = "sv")
    fun `4x4 localizes the School label and time headings in Swedish`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(340.dp, 280.dp))
            val primary = journeyRow(
                now.plusSeconds(180),
                lineDesignation = "13",
                transferCount = 1,
                legBadges = listOf(
                    WidgetJourneyLegBadge("13", TransportMode.METRO, "Mälarhöjden"),
                    WidgetJourneyLegBadge("41", TransportMode.TRAIN, "Stockholms södra"),
                ),
            )
            provideComposable { BlickWidgetContent(activeRoutineState(primary, null, label = RoutineLabel.STUDY), now) }

            onNode(hasTextEqualTo("Skola")).assertExists()
            onNode(hasTextEqualTo("••••••••••••••••••••••••")).assertExists()
            onNode(hasTextEqualTo("Avgång")).assertExists()
            onNode(hasTextEqualTo("Ankomst")).assertExists()
        }

    // ---- resolveEffectiveModel: the same 4-case matrix BlickRoutineWidgetTest proves as a pure
    // function -- this is the complementary proof that ActiveRoutineContent truly calls it and
    // renders whatever it resolves to, using the new JourneyCompositionRow/NextJourneyRow layout. ----

    @Test
    fun `both primary and secondary current -- header shows primary's line, both countdowns render`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, next), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasTextEqualTo(countdownText(5))).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            onNode(hasTextEqualTo(countdownText(7))).assertExists()
        }

    @Test
    fun `only primary current -- expired secondary is dropped, primary still leads`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val expiredSecondary = journeyRow(now.minusSeconds(1), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, expiredSecondary), now) }

            onNode(hasText("14")).assertExists()
            onNode(hasTextEqualTo(countdownText(5))).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
        }

    @Test
    fun `only secondary current -- promoted into the primary countdown AND the header badge, keeping its own role`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val expiredPrimary = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(expiredPrimary, next), now) }

            // Header badge follows the promoted row, not the original (now-expired) primary.
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertExists()
            // The promoted row drives the PRIMARY countdown/composition, not the secondary NEXT
            // one -- there is nothing left to demote it under, and it is direct (transferCount=0
            // by this file's own journeyRow default), so it reads "Direct", not a Next/Alternative
            // row.
            onNode(hasTextEqualTo(countdownText(7))).assertExists()
            onNode(hasText(directText())).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
        }

    @Test
    fun `neither current -- unavailable body, no stale line badge for either original row`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            // Different (both expired) departure instants -- so the two countdown-absence
            // assertions below are genuinely independent checks, not the same string asked
            // about twice.
            val expiredPrimary = journeyRow(now.minusSeconds(1), lineDesignation = "14")
            val expiredSecondary = journeyRow(now.minusSeconds(90), lineDesignation = "57", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(expiredPrimary, expiredSecondary), now) }

            onNode(hasText(context.getString(R.string.notification_unavailable))).assertExists()
            onNode(hasText("14")).assertDoesNotExist()
            onNode(hasText("57")).assertDoesNotExist()
            // Neither row is rendered as a live "0 min" countdown.
            onNode(hasTextEqualTo(countdownText(0))).assertDoesNotExist()
        }

    // ---- Direct/Both/With-changes: switched ONLY on RoutineWidgetContent.Journeys.changesPreference
    // -- the persisted routine preference -- never inferred from the journey's own transferCount
    // (see JourneyMainContent's own doc). ----

    @Test
    fun `DIRECT_ONLY renders a single line badge and the Direct label, never an Arrive-with-changes line`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(540), lineDesignation = "14", transportMode = TransportMode.METRO)
            val next = journeyRow(now.plusSeconds(1140), lineDesignation = "14", transportMode = TransportMode.METRO, role = JourneyRole.NEXT)
            provideComposable {
                BlickWidgetContent(activeRoutineState(primary, next, ExactDestinationChangesPreference.DIRECT_ONLY), now)
            }

            onNode(hasTextEqualTo(countdownText(9))).assertExists()
            onNode(hasText("14")).assertExists()
            onNode(hasText(directText())).assertExists()
            onNode(hasText(withChangesText())).assertDoesNotExist()
            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            onNode(hasTextEqualTo(countdownText(19))).assertExists()
        }

    @Test
    fun `BOTH renders multiple line badges and the Arrive-with-changes line, with no With-changes label`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val arrival = now.plusSeconds(1800)
            val primary = journeyRow(
                now.plusSeconds(300), lineDesignation = "14", transportMode = TransportMode.METRO,
                transferCount = 1, legBadges = listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
            ).copy(arrivalTime = arrival)
            val next = journeyRow(now.plusSeconds(720), lineDesignation = "14", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, next, ExactDestinationChangesPreference.BOTH), now) }

            onNode(hasTextEqualTo(countdownText(5))).assertExists()
            onNode(hasText("14")).assertExists()
            onNode(hasText("40")).assertExists()
            onNode(hasText(directText())).assertDoesNotExist()
            onNode(hasText(arriveWithChangesText(arrival, 1))).assertExists()
            onNode(hasText(withChangesText())).assertDoesNotExist()
            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            onNode(hasTextEqualTo(countdownText(12))).assertExists()
        }

    @Test
    fun `WITH_CHANGES_ONLY renders the same journey layout as BOTH, plus the small green With-changes label`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val arrival = now.plusSeconds(1800)
            val primary = journeyRow(
                now.plusSeconds(300), lineDesignation = "14", transportMode = TransportMode.METRO,
                transferCount = 1, legBadges = listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
            ).copy(arrivalTime = arrival)
            val next = journeyRow(now.plusSeconds(720), lineDesignation = "14", role = JourneyRole.NEXT)
            provideComposable {
                BlickWidgetContent(activeRoutineState(primary, next, ExactDestinationChangesPreference.WITH_CHANGES_ONLY), now)
            }

            onNode(hasTextEqualTo(countdownText(5))).assertExists()
            onNode(hasText("14")).assertExists()
            onNode(hasText("40")).assertExists()
            onNode(hasText(arriveWithChangesText(arrival, 1))).assertExists()
            // The one visible difference from the otherwise-identical BOTH rendering above.
            onNode(hasText(withChangesText())).assertExists()
        }

    @Test
    fun `WITH_CHANGES_ONLY correctly pluralizes a two-change journey`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val arrival = now.plusSeconds(2400)
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14", transferCount = 2).copy(arrivalTime = arrival)
            provideComposable {
                BlickWidgetContent(activeRoutineState(primary, null, ExactDestinationChangesPreference.WITH_CHANGES_ONLY), now)
            }

            onNode(hasText(arriveWithChangesText(arrival, 2))).assertExists()
            // Never the singular wording for a two-change journey.
            onNode(hasText(arriveWithChangesText(arrival, 1))).assertDoesNotExist()
        }

    @Test
    fun `a preference-less (defaulted BOTH) direct primary reads Direct, not zero changes`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            // changesPreference deliberately omitted -- defaults to BOTH, matching state
            // persisted by a version predating this field.
            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasText(directText())).assertExists()
        }

    // ---- Backend-authoritative role decides the second row's own wording -- never assumed from
    // list position, and never the same label for both cases, under any of the three preferences. ----

    @Test
    fun `a NEXT-role secondary row renders the Next label, not Alternative`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val next = journeyRow(now.plusSeconds(420), lineDesignation = "14", role = JourneyRole.NEXT)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, next), now) }

            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            onNode(hasTextEqualTo(alternativeLabelText())).assertDoesNotExist()
        }

    @Test
    fun `an ALTERNATIVE-role secondary row visibly renders the Alternative label, not Next`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            val alternative = journeyRow(now.plusSeconds(420), lineDesignation = "57", role = JourneyRole.ALTERNATIVE)
            provideComposable { BlickWidgetContent(activeRoutineState(primary, alternative), now) }

            onNode(hasTextEqualTo(alternativeLabelText())).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
        }

    @Test
    fun `DIRECT_ONLY still shows an ALTERNATIVE-role secondary as Alternative, not Next`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            // A direct-only routine's ALTERNATIVE is still a genuinely different (but still
            // direct) route -- see backend/src/services/candidateCollector.ts's own doc: the
            // preference only ever excludes journeys WITH changes, never ALTERNATIVE itself.
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14", transportMode = TransportMode.METRO)
            val alternative = journeyRow(now.plusSeconds(420), lineDesignation = "4", transportMode = TransportMode.BUS, role = JourneyRole.ALTERNATIVE)
            provideComposable {
                BlickWidgetContent(activeRoutineState(primary, alternative, ExactDestinationChangesPreference.DIRECT_ONLY), now)
            }

            onNode(hasTextEqualTo(alternativeLabelText())).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertDoesNotExist()
        }

    // ---- Robustness: longer station names, up to two changes, and two-digit countdowns must
    // not break the layout or these key text elements. ----

    @Test
    fun `a long destination name, two changes, and a two-digit countdown all render without dropping key text`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val arrival = now.plusSeconds(3600)
            val primary = journeyRow(
                now.plusSeconds(4260), lineDesignation = "42X", transportMode = TransportMode.TRAIN, transferCount = 2,
                legBadges = listOf(
                    WidgetJourneyLegBadge("42X", TransportMode.TRAIN),
                    WidgetJourneyLegBadge("4", TransportMode.BUS),
                    WidgetJourneyLegBadge("19", TransportMode.METRO),
                ),
            ).copy(arrivalTime = arrival)
            val next = journeyRow(now.plusSeconds(6600), lineDesignation = "42X", role = JourneyRole.NEXT)
            val state = RoutineWidgetUiState.ActiveRoutine(
                RoutineWidgetModel(
                    routineId = "r1",
                    routineName = "Long commute",
                    stationName = "Kungsträdgården",
                    directionLabel = "Mörby centrum via Universitetet and Näckrosdammen",
                    content = RoutineWidgetContent.Journeys(primary, next, ExactDestinationChangesPreference.WITH_CHANGES_ONLY),
                    lineDesignation = primary.lineDesignation,
                    transportMode = primary.transportMode,
                ),
            )
            provideComposable { BlickWidgetContent(state, now) }

            // 71-minute primary countdown -- displayed as localized hours and minutes.
            onNode(hasTextEqualTo(countdownText(71))).assertExists()
            // hasTextEqualTo, not the substring-matching hasText: "4" is itself a substring of
            // the "42X" badge's own text, so a substring search for "4" would ambiguously match
            // both badges.
            onNode(hasTextEqualTo("42X")).assertExists()
            onNode(hasTextEqualTo("4")).assertExists()
            onNode(hasTextEqualTo("19")).assertExists()
            onNode(hasText(arriveWithChangesText(arrival, 2))).assertExists()
            onNode(hasText(withChangesText())).assertExists()
            onNode(hasTextEqualTo(nextLabelText())).assertExists()
            // 110-minute secondary countdown -- also displayed as hours and minutes.
            onNode(hasTextEqualTo(countdownText(110))).assertExists()
        }

    // ---- Disruption strip: a CONFIRMED/LINE_DIRECTION disruption renders its own classified
    // effect as a short, localized category label -- NEVER the raw SL headline (see
    // disruptionStripText's own doc: SL's free text is never machine-translated, so it stays
    // reserved for Routine Details' own full-text display). A LINE_RELEVANT one instead renders
    // the same conservative "Line X disruption" label the notification shows -- see the
    // Akalla -> T-Centralen false-positive this exists to prevent. These use a tall Standard size;
    // the matching Large behavior has dedicated regression coverage above. ----

    @Test
    fun `a CONFIRMED disruption renders its classified effect's localized category, never the raw SL headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Hissen är ur funktion.",
                        disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
                    ),
                    now,
                )
            }

            onNode(hasText(effectText(DisruptionEffect.ACCESSIBILITY_ISSUE))).assertExists()
            onNode(hasText("Hissen är ur funktion.")).assertDoesNotExist()
        }

    @Test
    @Config(qualifiers = "sv")
    fun `a CONFIRMED disruption in Swedish renders the Swedish classified category, never the raw SL headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Hissen är ur funktion.",
                        disruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
                    ),
                    now,
                )
            }

            val swedishLabel = effectText(DisruptionEffect.ACCESSIBILITY_ISSUE)
            assertEquals("Tillgänglighetsproblem", swedishLabel)
            onNode(hasText(swedishLabel)).assertExists()
            onNode(hasText("Hissen är ur funktion.")).assertDoesNotExist()
        }

    @Test
    fun `a DELAYS-classified disruption renders the Delays category label`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Försenad avgång från Slussen",
                        disruptionEffect = DisruptionEffect.DELAYS,
                    ),
                    now,
                )
            }

            val delaysLabel = effectText(DisruptionEffect.DELAYS)
            assertEquals("Delays", delaysLabel)
            onNode(hasText(delaysLabel)).assertExists()
        }

    @Test
    fun `a LINE_RELEVANT disruption with one matched line renders the conservative Line X disruption label, never the classified effect or the raw headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
                        disruptionUncertainLineDesignations = listOf("11"),
                        // A specific, confident effect -- LINE_RELEVANT must still win: this
                        // effect was never proven to apply to THIS exact journey, only to the
                        // line/mode in general (see disruptionStripText's own doc).
                        disruptionEffect = DisruptionEffect.NO_SERVICE,
                    ),
                    now,
                )
            }

            onNode(hasText(lineRelevantSingleText("11"))).assertExists()
            onNode(hasText("Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården")).assertDoesNotExist()
            onNode(hasText(effectText(DisruptionEffect.NO_SERVICE))).assertDoesNotExist()
        }

    @Test
    fun `a LINE_RELEVANT disruption with multiple matched lines falls back to the generic Line disruption label`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Trafikstörning",
                        disruptionUncertainLineDesignations = listOf("11", "17"),
                    ),
                    now,
                )
            }

            onNode(hasText(lineRelevantGenericText())).assertExists()
        }

    @Test
    fun `a disruption persisted before disruptionEffect existed renders the generic Disruption label, never the raw Swedish headline`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    // disruptionEffect deliberately omitted -- defaults to null, matching state
                    // an older app version persisted: the headline is real, but its own
                    // classification was never written for this widget instance yet.
                    activeRoutineState(primary, null, disruptionHeadline = "Hissen är ur funktion."),
                    now,
                )
            }

            val genericLabel = effectText(DisruptionEffect.DISRUPTION)
            assertEquals("Disruption", genericLabel)
            onNode(hasText(genericLabel)).assertExists()
            onNode(hasText("Hissen är ur funktion.")).assertDoesNotExist()
        }

    @Test
    fun `no disruption renders no disruption strip at all`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 200.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable { BlickWidgetContent(activeRoutineState(primary, null), now) }

            onNode(hasText(lineRelevantGenericText())).assertDoesNotExist()
        }

    @Test
    fun `a compact size never renders the disruption strip, CONFIRMED or LINE_RELEVANT alike`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            // Below the canonical 3x2 height threshold, so this resolves to the small tier.
            setAppWidgetSize(DpSize(300.dp, 90.dp))
            val primary = journeyRow(now.plusSeconds(300), lineDesignation = "14")
            provideComposable {
                BlickWidgetContent(
                    activeRoutineState(
                        primary, null,
                        disruptionHeadline = "Inställd trafik",
                        disruptionUncertainLineDesignations = listOf("11"),
                    ),
                    now,
                )
            }

            onNode(hasText("Inställd trafik")).assertDoesNotExist()
            onNode(hasText(lineRelevantSingleText("11"))).assertDoesNotExist()
        }
}
