package se.blick.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeTest {
    @Test
    fun `nullable preference maps to System Light Dark and back`() {
        assertEquals(AppearanceMode.System, AppearanceMode.from(null))
        assertEquals(AppearanceMode.Light, AppearanceMode.from(false))
        assertEquals(AppearanceMode.Dark, AppearanceMode.from(true))
        assertEquals(
            AppearanceMode.StockholmNight,
            AppearanceMode.from(
                useDarkTheme = false,
                useStockholmNightTheme = true,
                hasPremiumAccess = true,
            ),
        )
        assertEquals(
            AppearanceMode.Light,
            AppearanceMode.from(
                useDarkTheme = false,
                useStockholmNightTheme = true,
                hasPremiumAccess = false,
            ),
        )

        assertEquals(null, AppearanceMode.System.useDarkTheme)
        assertEquals(false, AppearanceMode.Light.useDarkTheme)
        assertEquals(true, AppearanceMode.Dark.useDarkTheme)
        assertEquals(true, AppearanceMode.StockholmNight.useDarkTheme)
    }

    @Test
    fun `Stockholm night background requires both a saved request and Premium access`() {
        assertEquals(true, shouldUseStockholmNightTheme(requested = true, hasPremiumAccess = true))
        assertEquals(false, shouldUseStockholmNightTheme(requested = true, hasPremiumAccess = false))
        assertEquals(false, shouldUseStockholmNightTheme(requested = false, hasPremiumAccess = true))
    }
}
