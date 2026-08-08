package se.blick.app.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList as PlatformLocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R
import java.util.Locale

/**
 * [effectiveBlickLocale] is the pure decision at the heart of Blick's presentation-locale rule —
 * see that function's own doc. No Android/Robolectric dependency at all: it takes plain
 * [Locale]/[String] values and returns a plain [Locale], so this runs as an ordinary fast JVM
 * test, independent of [AppLocaleTest] below (which exercises the Android-facing
 * [Context.withAppLocale] wrapped around this same function).
 */
class EffectiveBlickLocaleTest {

    private fun locales(vararg tags: String): List<Locale> = tags.map { Locale.forLanguageTag(it) }

    @Test
    fun `no explicit choice, system is en -- effective is English`() {
        assertEquals("en", effectiveBlickLocale(null, locales("en")).language)
    }

    @Test
    fun `no explicit choice, system is sv -- effective is Swedish`() {
        assertEquals("sv", effectiveBlickLocale(null, locales("sv")).language)
    }

    @Test
    fun `no explicit choice, system is lt -- effective is English`() {
        assertEquals("en", effectiveBlickLocale(null, locales("lt")).language)
    }

    @Test
    fun `explicit en, system is sv -- explicit wins, effective is English`() {
        assertEquals("en", effectiveBlickLocale("en", locales("sv")).language)
    }

    @Test
    fun `explicit sv, system is en -- explicit wins, effective is Swedish`() {
        assertEquals("sv", effectiveBlickLocale("sv", locales("en")).language)
    }

    @Test
    fun `explicit sv, system is lt -- explicit wins, effective is Swedish`() {
        assertEquals("sv", effectiveBlickLocale("sv", locales("lt")).language)
    }

    // ---- Ordered system locale list -- Android resource resolution itself skips a preferred
    // but unsupported system language and falls through to the next one an app has resources
    // for, rather than jumping straight to the app's own ultimate default; this pure selector
    // must mirror that same order-respecting behavior ----

    @Test
    fun `no explicit choice, system is lt then sv -- Swedish is the first supported entry`() {
        assertEquals("sv", effectiveBlickLocale(null, locales("lt", "sv")).language)
    }

    @Test
    fun `no explicit choice, system is lt then en -- English is the first supported entry`() {
        assertEquals("en", effectiveBlickLocale(null, locales("lt", "en")).language)
    }

    @Test
    fun `no explicit choice, system is lt then pl -- neither entry is supported, falls back to English`() {
        assertEquals("en", effectiveBlickLocale(null, locales("lt", "pl")).language)
    }

    @Test
    fun `no explicit choice, system is lt then pl then sv -- Swedish is the first supported entry despite being third`() {
        assertEquals("sv", effectiveBlickLocale(null, locales("lt", "pl", "sv")).language)
    }

    // ---- Edge cases beyond the required scenarios ----

    @Test
    fun `no explicit choice, empty system list -- falls back to English`() {
        assertEquals("en", effectiveBlickLocale(null, emptyList()).language)
    }

    @Test
    fun `explicit sv, empty system list -- explicit still wins`() {
        assertEquals("sv", effectiveBlickLocale("sv", emptyList()).language)
    }
}

/**
 * [Context.withAppLocale] is the one shared mechanism [se.blick.app.notification.RoutineNotificationBuilder]
 * and the home-screen widget both rely on for a long-lived, non-Activity context to resolve
 * Blick's own selected app language rather than the device's — see that function's own doc.
 *
 * `@Config(sdk = [26])`, not this project's usual Robolectric pin of 34 (see
 * `libs.versions.toml`'s `robolectric` entry) — Robolectric does not fully shadow the framework
 * `LocaleManager` both [AppCompatDelegate] and [LocaleManagerCompat] delegate to on API 33+, so
 * neither one round-trips `setApplicationLocales` through its own `getApplicationLocales` in
 * this test environment at that level (verified directly with a throwaway diagnostic test, since
 * removed — not a production bug: on a real Android 13+ device the framework itself is the
 * single, always-current source of truth for both APIs, Activity or not). 26 exercises
 * AppCompat's own pre-33 compat storage instead — real Kotlin/Java state, not a platform shadow —
 * which is also the exact code path [Context.withAppLocale]'s manual [Configuration] wrapping
 * exists for in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = android.app.Application::class)
class AppLocaleTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun clearAppLocale() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `with no explicit app locale ever set, withAppLocale returns the exact same context`() {
        assertSame(context, context.withAppLocale())
    }

    // ---- No explicit choice -- system locale list drives the fallback (see
    // EffectiveBlickLocaleTest above for the pure rule; these exercise the real Context/
    // Configuration wiring around it) ----

    @Test
    @Config(sdk = [26], qualifiers = "sv")
    fun `system locale Swedish with no explicit Blick choice resolves Swedish via ordinary resource resolution alone`() {
        // Sanity check that the simulated device locale really is Swedish before asserting
        // withAppLocale() needed to do nothing special to already agree with it.
        assertEquals("sv", context.resources.configuration.locales[0].language)

        val wrapped = context.withAppLocale()

        assertSame(context, wrapped)
        assertEquals("Språk", wrapped.getString(R.string.settings_language_label))
    }

    @Test
    @Config(sdk = [26], qualifiers = "lt")
    fun `unsupported Lithuanian system locale with no explicit Blick choice normalizes withAppLocale to English`() {
        assertEquals("lt", context.resources.configuration.locales[0].language)

        val wrapped = context.withAppLocale()

        assertEquals("en", wrapped.resources.configuration.locales[0].language)
        assertEquals("Language", wrapped.getString(R.string.settings_language_label))
    }

    /** Builds a [Context] whose [Configuration] carries a genuine, ordered, multi-entry system
     * locale list — [Config.qualifiers] cannot express this (Robolectric's qualifier-string
     * parser only accepts a single locale qualifier), so this constructs the [Configuration]
     * directly via the same public [android.os.LocaleList]/[Context.createConfigurationContext]
     * APIs Android itself uses, independent of any AndroidX/AppCompat internals. */
    private fun contextWithSystemLocales(vararg tags: String): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(PlatformLocaleList(*tags.map { Locale.forLanguageTag(it) }.toTypedArray()))
        }
        return context.createConfigurationContext(configuration)
    }

    @Test
    fun `withAppLocale on an ordered system locale list Lithuanian-then-Swedish resolves Swedish, not English`() {
        val multiLocaleContext = contextWithSystemLocales("lt", "sv")
        assertEquals(listOf("lt", "sv"), (0 until 2).map { multiLocaleContext.resources.configuration.locales[it].language })

        val wrapped = multiLocaleContext.withAppLocale()

        assertEquals("sv", wrapped.resources.configuration.locales[0].language)
        assertEquals("Språk", wrapped.getString(R.string.settings_language_label))
    }

    @Test
    fun `withAppLocale on an ordered system locale list Lithuanian-then-Polish falls back to English`() {
        val multiLocaleContext = contextWithSystemLocales("lt", "pl")

        val wrapped = multiLocaleContext.withAppLocale()

        assertEquals("en", wrapped.resources.configuration.locales[0].language)
        assertEquals("Language", wrapped.getString(R.string.settings_language_label))
    }

    // ---- The actual fix: withAppLocale must source the explicit choice safely from a
    // background/non-Activity Context, not from AppCompatDelegate's Activity-lifecycle-dependent
    // in-memory field ----

    @Test
    fun `withAppLocale follows the context-safe LocaleManagerCompat view, not AppCompatDelegate's in-memory-only field`() {
        // Calling setApplicationLocales() with no AppCompatActivity ever created in this process
        // updates ONLY AppCompatDelegate's static sRequestedAppLocales field. Verified directly
        // from AppCompatDelegateImpl's own source: both the load (attachBaseContext2's
        // syncRequestedAndStoredLocales, "performed only during cold app start-ups") and the
        // store (applyAppLocales' asyncExecuteSyncRequestedAndStoredLocales, "only reached when
        // there is an explicit call to setApplicationLocales()") require an active delegate
        // attached to a real base context -- neither runs here. So this call deliberately does
        // NOT reach the persisted-locales file LocaleManagerCompat.getApplicationLocales reads --
        // the exact real-world condition (a WorkManager-started process where MainActivity has
        // never run) this fix targets.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("sv"))
        assertFalse(
            "expected setApplicationLocales to actually update AppCompatDelegate's own in-memory field",
            AppCompatDelegate.getApplicationLocales().isEmpty,
        )
        assertTrue(
            "expected the context-safe path to NOT see this in-process-only, never-persisted choice",
            LocaleManagerCompat.getApplicationLocales(context).isEmpty,
        )

        val wrapped = context.withAppLocale()

        // Falls through to the (default, English) system-locale fallback -- proof that
        // withAppLocale() consulted the context-safe view above, not AppCompatDelegate's
        // in-memory one, which -- if consulted -- would have produced Swedish here instead.
        assertEquals("en", wrapped.resources.configuration.locales[0].language)
    }
}
