package se.blick.app.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/** Tests translated platform views: the composition tree alone does not catch child truncation. */
@RunWith(AndroidJUnit4::class)
class LargeWidgetRemoteViewsTest {
    @Test
    fun nextDepartureSurvivesTranslationAtBothLargeSizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val now = Instant.parse("2026-09-05T08:00:00Z")
        val primary = WidgetJourneyRow(
            lineDesignation = "13",
            transportMode = TransportMode.METRO,
            departureTime = now.plusSeconds(720),
            arrivalTime = now.plusSeconds(2400),
            transferCount = 1,
            isRealtime = true,
            role = JourneyRole.PRIMARY,
            legBadges = listOf(
                WidgetJourneyLegBadge("13", TransportMode.METRO, "Mälarhöjden"),
                WidgetJourneyLegBadge("41", TransportMode.TRAIN, "Stockholms södra"),
            ),
        )
        val next = primary.copy(
            departureTime = now.plusSeconds(1320),
            arrivalTime = now.plusSeconds(3000),
            role = JourneyRole.NEXT,
        )
        val state = RoutineWidgetUiState.ActiveRoutine(
            RoutineWidgetModel(
                routineId = "large-widget-test",
                routineName = "Commute",
                stationName = "Mälarhöjden",
                directionLabel = "Tumba",
                content = RoutineWidgetContent.Journeys(primary, next),
            ),
        )
        val widget = object : GlanceAppWidget() {
            override suspend fun provideGlance(context: Context, id: GlanceId) {
                provideContent { BlickWidgetContent(state, now, useStockholmNightTheme = true) }
            }
        }
        val expected = "${context.getString(R.string.widget_journey_next_label)} " +
            "${formatWidgetCountdown(context, 22)}  ›"
        for (height in listOf(280, 360)) {
            val views = widget.compose(context, size = DpSize(340.dp, height.dp))
            instrumentation.runOnMainSync {
                val root = views.apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((340 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val row = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == expected }
                assertTrue("Next departure missing at height $height", row != null)
                val bounds = android.graphics.Rect()
                assertTrue("Next departure clipped at height $height", row!!.getLocalVisibleRect(bounds))
                assertTrue("Next departure partially clipped at height $height", bounds.height() == row.height)
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
