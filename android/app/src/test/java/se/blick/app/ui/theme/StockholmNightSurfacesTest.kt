package se.blick.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StockholmNightSurfacesTest {

    @Test
    fun `foreground surfaces are opaque navy layers rather than transparent wallpaper overlays`() {
        val surfaces = listOf(
            StockholmNightSurfaces.Card,
            StockholmNightSurfaces.Control,
            StockholmNightSurfaces.SelectedControl,
        )

        surfaces.forEach { surface ->
            assertEquals(1f, surface.alpha)
            assertNotEquals(Color.Black, surface)
            assertNotEquals(Color.Transparent, surface)
        }
        assertNotEquals(StockholmNightSurfaces.Card, StockholmNightSurfaces.Control)
        assertNotEquals(StockholmNightSurfaces.Control, StockholmNightSurfaces.SelectedControl)
    }

    @Test
    fun `surface borders and dividers are also fully opaque`() {
        assertEquals(1f, StockholmNightSurfaces.Border.alpha)
        assertEquals(1f, StockholmNightSurfaces.Divider.alpha)
    }
}
