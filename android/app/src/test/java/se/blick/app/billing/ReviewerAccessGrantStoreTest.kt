package se.blick.app.billing

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReviewerAccessGrantStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(
            PreferencesReviewerAccessGrantStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences("premium_entitlement_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `grant survives a new store instance and ordinary app update storage semantics`() {
        PreferencesReviewerAccessGrantStore(context).writeActive(true)

        val restored = PreferencesReviewerAccessGrantStore(context)

        assertTrue(restored.readActive())
    }

    @Test
    fun `reviewer grant storage is independent from purchased entitlement cache`() {
        val grantStore = PreferencesReviewerAccessGrantStore(context)
        grantStore.writeActive(true)
        context.getSharedPreferences("premium_entitlement_cache", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("last_verified_premium", false)
            .putBoolean("has_verified_entitlement", true)
            .commit()

        assertTrue(PreferencesReviewerAccessGrantStore(context).readActive())

        grantStore.writeActive(false)

        assertFalse(grantStore.readActive())
        assertTrue(
            context.getSharedPreferences("premium_entitlement_cache", Context.MODE_PRIVATE)
                .getBoolean("has_verified_entitlement", false),
        )
    }
}
