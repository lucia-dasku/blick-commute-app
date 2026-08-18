package se.blick.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeTest {
    @Test
    fun `nullable preference maps to System Light Dark and back`() {
        assertEquals(AppearanceMode.System, AppearanceMode.from(null))
        assertEquals(AppearanceMode.Light, AppearanceMode.from(false))
        assertEquals(AppearanceMode.Dark, AppearanceMode.from(true))

        assertEquals(null, AppearanceMode.System.useDarkTheme)
        assertEquals(false, AppearanceMode.Light.useDarkTheme)
        assertEquals(true, AppearanceMode.Dark.useDarkTheme)
    }
}
