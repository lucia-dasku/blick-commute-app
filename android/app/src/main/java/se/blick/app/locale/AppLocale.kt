package se.blick.app.locale

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocaleList
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * A [Context] resolving [android.content.res.Resources] (strings, plurals, locale-sensitive
 * formatting) against [effectiveBlickLocale] — Blick's own selected app language, English or
 * Svenska — not necessarily the device's raw system locale. See [effectiveBlickLocale]'s own
 * doc for the English-vs-Swedish rule this applies.
 *
 * Needed only for a long-lived, non-Activity context: [se.blick.app.notification.RoutineNotificationBuilder]'s
 * injected `@ApplicationContext`, and the home-screen widget's Glance `LocalContext` (see
 * `widget/BlickRoutineWidget.kt`). Deliberately does NOT read the explicit app locale via
 * [AppCompatDelegate.getApplicationLocales] — that method's own documented contract is "should
 * always be called after Activity.onCreate()", and on Android 8-12 its backing storage is an
 * in-memory field only ever populated by an Activity/delegate having actually run in THIS
 * process. A WorkManager-started process that has never created [se.blick.app.MainActivity]
 * would see it as empty even though the user's choice IS persisted to disk (this project's
 * `autoStoreLocales` is on) — silently reporting "no explicit choice" for a routine whose
 * language was, in fact, explicitly set. [LocaleManagerCompat.getApplicationLocales] reads that
 * same persisted choice directly via [context] instead, safe from any Context regardless of
 * Activity lifecycle: on API 33+ it queries the real framework `LocaleManager`; below that it
 * reads AppCompat's own persisted-locales file directly, with no dependency on any in-memory
 * field ever having been hydrated by an Activity.
 *
 * For a Compose screen hosted in [se.blick.app.MainActivity] (an
 * [androidx.appcompat.app.AppCompatActivity]), use [currentBlickLocale] instead —
 * [AppCompatDelegate.getApplicationLocales] is safe there (an Activity has, by construction,
 * already run `onCreate()`), and reading it there stays Compose-reactive by riding along with
 * [LocalLocaleList]'s own recomposition.
 */
fun Context.withAppLocale(): Context {
    val platformLocales = resources.configuration.locales
    val systemLocales = List(platformLocales.size()) { platformLocales[it] }
    val explicitLanguage = LocaleManagerCompat.getApplicationLocales(this).firstLanguageOrNull()
    val effective = effectiveBlickLocale(explicitLanguage, systemLocales)
    if (systemLocales.firstOrNull()?.language == effective.language) return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(effective)
    return createConfigurationContext(configuration)
}

/**
 * Blick's effective presentation locale for the currently-composing screen — see
 * [effectiveBlickLocale] for the underlying rule. Safe to call from any Compose screen hosted in
 * [se.blick.app.MainActivity]: [AppCompatDelegate.getApplicationLocales] is documented to work
 * correctly once `Activity.onCreate()` has run, which is always true here (contrast
 * [withAppLocale], which deliberately avoids that same call for exactly the opposite reason).
 * Stays reactive to a language switch or a system locale change despite
 * [AppCompatDelegate.getApplicationLocales] itself not being a Compose-observable read: this
 * function also reads [LocalLocaleList], which IS Compose-observable and recomposes on exactly
 * the same events that change the explicit choice too (an
 * [AppCompatDelegate.setApplicationLocales] call recreates the Activity on Android 8-12 and
 * triggers a configuration change on 13+; either way this whole composable re-runs, so the
 * plain [AppCompatDelegate] read below is never stale when it actually matters).
 */
@Composable
fun currentBlickLocale(): Locale {
    val systemLocales = LocalLocaleList.current.map { it.platformLocale }
    val explicitLanguage = AppCompatDelegate.getApplicationLocales().firstLanguageOrNull()
    return effectiveBlickLocale(explicitLanguage, systemLocales)
}

/** The first entry's language tag, or null if empty — shared by [withAppLocale] and
 * [currentBlickLocale], whose two different-but-equally-safe sources for the explicit app
 * locale ([LocaleManagerCompat.getApplicationLocales] and [AppCompatDelegate.getApplicationLocales]
 * respectively) both return this same [LocaleListCompat] type. */
private fun LocaleListCompat.firstLanguageOrNull(): String? = if (isEmpty) null else get(0)?.language

private val SWEDISH: Locale = Locale.forLanguageTag("sv")
private val ENGLISH: Locale = Locale.forLanguageTag("en")

/**
 * Blick's effective presentation locale: always exactly English or Svenska, never a raw,
 * possibly-unsupported locale left unmodified. The one centralized rule behind [withAppLocale]
 * and [currentBlickLocale] — a single, pure, fully-testable decision replacing scattered
 * per-screen/per-context locale reads:
 *
 * 1. [explicitLanguage] (an explicit choice from the Settings language chips, via
 *    [AppCompatDelegate.setApplicationLocales]) always wins when present — `"sv"` resolves to
 *    Swedish, anything else (in practice always `"en"`, the only other value Blick's own
 *    Settings screen ever sets) resolves to English.
 * 2. Otherwise, [systemLocales] — the device's own ORDERED locale preference list, e.g.
 *    Lithuanian-then-Swedish for a phone with Lithuanian as its primary language and Swedish as
 *    a secondary one — is walked in order for the first entry Blick actually supports. This
 *    mirrors ordinary Android resource resolution, which likewise skips a preferred-but-
 *    unsupported system language and falls through to the next one the app has resources for
 *    rather than jumping straight to the app's ultimate default: Lithuanian-then-Swedish must
 *    resolve to Swedish, not English, for the same reason the Swedish resource directory (not
 *    the default one) is what actually renders strings on such a device today.
 * 3. If neither an explicit choice nor any supported system locale exists, English — Blick's
 *    own resource default.
 *
 * Purely a presentation-time computation: an unsupported locale list resolving to English here
 * never calls `setApplicationLocales` and never persists anything — [AppCompatDelegate] remains
 * the only source of truth for an actual explicit choice, so this has nothing stale to
 * invalidate if the system locale list or the explicit choice changes later.
 */
fun effectiveBlickLocale(explicitLanguage: String?, systemLocales: List<Locale>): Locale {
    val language = explicitLanguage
        ?: systemLocales.firstOrNull { it.language == "sv" || it.language == "en" }?.language
    return if (language == "sv") SWEDISH else ENGLISH
}
