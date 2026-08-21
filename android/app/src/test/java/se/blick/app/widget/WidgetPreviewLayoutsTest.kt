package se.blick.app.widget

import android.content.ComponentName
import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.xmlpull.v1.XmlPullParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R

/**
 * Regression coverage for the exact failure mode that broke the "Large" entry in Samsung's
 * widget picker: `android:previewLayout` (see `res/xml/blick_routine_widget_info*.xml`) is
 * inflated by the platform through [RemoteViews], NOT a plain [android.view.LayoutInflater]
 * pass in this app's own process — the same restricted view-class allowlist
 * `android:initialLayout` and the real widget's own rendering are already bound by (no plain
 * [android.view.View], no arbitrary custom views). A bare `<View>` for the "Live" status dot in
 * `widget_preview_large.xml` slipped past every other check (it inflates fine under an ordinary
 * `LayoutInflater`, and there is no local symptom in Android Studio's own layout preview) and
 * only surfaced at the picker as "Couldn't add widget." Constructing a real [RemoteViews] for
 * each of the three preview layouts and calling [RemoteViews.apply] here reproduces that exact
 * production code path (the same one `AppWidgetHostView`/the platform's own widget-preview
 * renderer uses) directly in a fast JVM test, so any future edit reintroducing an
 * unsupported view class in one of these three layouts fails a build instead of only surfacing
 * on a real device's widget picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetPreviewLayoutsTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun assertInflatesThroughRemoteViews(layoutResId: Int) {
        val remoteViews = RemoteViews(context.packageName, layoutResId)
        val parent = FrameLayout(context)
        val view = remoteViews.apply(context, parent)
        assertNotNull(view)
    }

    @Test
    fun `compact preview layout inflates through RemoteViews`() {
        assertInflatesThroughRemoteViews(R.layout.widget_preview_compact)
    }

    @Test
    fun `standard preview layout inflates through RemoteViews`() {
        assertInflatesThroughRemoteViews(R.layout.widget_preview_standard)
    }

    @Test
    fun `large preview layout inflates through RemoteViews`() {
        assertInflatesThroughRemoteViews(R.layout.widget_preview_large)
    }

    /** The platform widget picker's own per-size card title comes from the resolved
     * `android:label` of the RECEIVER component in `AndroidManifest.xml`, not from anything in
     * the `appwidget-provider` XML resource — before these three receivers each got their own
     * `android:label`, every entry fell back to the same application-default "Blick" label with
     * nothing distinguishing the three sizes in the picker. */
    private fun receiverLabel(receiverClass: Class<*>): String {
        val componentName = ComponentName(context, receiverClass)
        val receiverInfo = context.packageManager.getReceiverInfo(componentName, 0)
        return receiverInfo.loadLabel(context.packageManager).toString()
    }

    @Test
    fun `each widget size receiver resolves to its own distinct picker label`() {
        assertEquals("Blick Small", receiverLabel(BlickRoutineWidgetReceiverCompact::class.java))
        assertEquals("Blick Standard", receiverLabel(BlickRoutineWidgetReceiver::class.java))
        assertEquals("Blick Large", receiverLabel(BlickRoutineWidgetReceiverLarge::class.java))
    }

    private fun targetCells(providerInfoRes: Int): Pair<Int, Int> {
        val parser = context.resources.getXml(providerInfoRes)
        parser.use {
            while (it.eventType != XmlPullParser.START_TAG && it.eventType != XmlPullParser.END_DOCUMENT) it.next()
            val namespace = "http://schemas.android.com/apk/res/android"
            return it.getAttributeIntValue(namespace, "targetCellWidth", -1) to
                it.getAttributeIntValue(namespace, "targetCellHeight", -1)
        }
    }

    @Test
    fun `provider entries advertise exactly 2x2 3x2 and 4x4`() {
        assertEquals(2 to 2, targetCells(R.xml.blick_routine_widget_info_compact))
        assertEquals(3 to 2, targetCells(R.xml.blick_routine_widget_info))
        assertEquals(4 to 4, targetCells(R.xml.blick_routine_widget_info_large))
    }
}
