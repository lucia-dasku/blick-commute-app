package se.blick.app.ui.screens.onetimeevent

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.ui.components.visuals
import se.blick.app.ui.theme.StockholmNightSurfaces

class OneTimeEventLabelVisualsTest {

    private val routineLabelsByEventLabel = mapOf(
        OneTimeEventLabel.TRAVEL to RoutineLabel.WORK,
        OneTimeEventLabel.EVENT to RoutineLabel.GYM,
        OneTimeEventLabel.APPOINTMENT to RoutineLabel.STUDY,
        OneTimeEventLabel.OTHER to RoutineLabel.OTHER,
    )

    @Test
    fun `light and dark event label visuals reuse their Routine label color families`() {
        listOf(false, true).forEach { darkTheme ->
            routineLabelsByEventLabel.forEach { (eventLabel, routineLabel) ->
                val eventVisuals = eventLabel.visuals(darkTheme)
                val routineVisuals = routineLabel.visuals(darkTheme)

                assertEquals(routineVisuals.accent, eventVisuals.accent)
                assertEquals(routineVisuals.container, eventVisuals.unselectedContainer)
            }
        }
    }

    @Test
    fun `standard selected fills strengthen each labels own accent`() {
        OneTimeEventLabel.entries.forEach { label ->
            val light = label.chipPalette(darkTheme = false, useStockholmNightSurface = false)
            val dark = label.chipPalette(darkTheme = true, useStockholmNightSurface = false)

            assertEquals(light.accent.copy(alpha = 0.18f), light.selectedContainer)
            assertEquals(dark.accent.copy(alpha = 0.28f), dark.selectedContainer)
            assertTrue(light.selectedContainer.alpha > light.unselectedContainer.alpha)
            assertTrue(dark.selectedContainer.alpha > dark.unselectedContainer.alpha)
        }
    }

    @Test
    fun `Stockholm Night keeps control surfaces while tinting them with every label accent`() {
        OneTimeEventLabel.entries.forEach { label ->
            val palette = label.chipPalette(darkTheme = true, useStockholmNightSurface = true)

            assertEquals(
                palette.accent.copy(alpha = 0.08f).compositeOver(StockholmNightSurfaces.Control),
                palette.unselectedContainer,
            )
            assertEquals(
                palette.accent.copy(alpha = 0.18f).compositeOver(StockholmNightSurfaces.SelectedControl),
                palette.selectedContainer,
            )
            assertNotEquals(palette.accent, palette.unselectedContainer)
            assertNotEquals(palette.accent, palette.selectedContainer)
        }
    }
}
