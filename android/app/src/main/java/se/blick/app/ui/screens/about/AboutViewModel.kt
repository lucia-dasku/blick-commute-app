package se.blick.app.ui.screens.about

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import javax.inject.Inject

/**
 * Owns Blick's own explicit English/Svenska language selection — see
 * [se.blick.app.locale.withAppLocale]'s own doc for why [AppCompatDelegate.setApplicationLocales]
 * is the single source of truth, not a new [se.blick.app.data.local.datastore.AppSettingsDataStore]
 * field. Persistence, Activity recreation, and (Android 13+) system Settings integration all
 * come from AppCompat/the platform itself — this ViewModel's only real job is the one side
 * effect that doesn't happen automatically: refreshing the home-screen widget's own
 * already-placed instances, so an inactive/static widget updates immediately too rather than
 * waiting for its next unrelated refresh.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val routineWidgetUpdater: RoutineWidgetUpdater,
) : ViewModel() {

    /** [languageTag] is always "en" or "sv" — see [AboutScreen]'s own two-chip Language section.
     * Synchronous, like [AppCompatDelegate.setApplicationLocales] itself — the actual UI change
     * (this Activity recreating, on Android 8-12; the platform doing so on its own on 13+) is
     * not something this function waits on or needs to. */
    fun onLanguageSelected(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        // Best-effort, and deliberately NOT routineWidgetUpdater.reconcile() -- see
        // RoutineWidgetUpdater.refreshPresentation's own doc: this must never re-derive or
        // change what an active widget is showing, only redraw it in the new language.
        viewModelScope.launch {
            runWidgetUpdateSafely { routineWidgetUpdater.refreshPresentation() }
        }
    }
}
