package se.blick.app.billing

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PremiumRoutineOrderStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("premium_routine_order", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `saved order survives a new store instance`() {
        PreferencesPremiumRoutineOrderStore(context).saveOrder(listOf("gym", "home", "work"))

        val restored = PreferencesPremiumRoutineOrderStore(context)

        assertEquals(listOf("gym", "home", "work"), restored.orderedRoutineIds.value)
    }

    @Test
    fun `saving removes duplicate ids while retaining first occurrence order`() {
        val store = PreferencesPremiumRoutineOrderStore(context)

        store.saveOrder(listOf("home", "work", "home"))

        assertEquals(listOf("home", "work"), store.orderedRoutineIds.value)
    }
}
