package se.blick.app.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import se.blick.app.BuildConfig

class AdConfigurationDebugTest {

    @Test
    fun debugBuildUsesOnlyGooglesBannerTestUnit() {
        assertEquals(
            "ca-app-pub-3940256099942544/9214589741",
            BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
        )
        assertNotEquals(
            "ca-app-pub-2107592277107216/3654434815",
            BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
        )
    }
}
