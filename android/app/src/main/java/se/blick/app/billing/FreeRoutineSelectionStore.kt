package se.blick.app.billing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeRoutineSelectionStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("free_tier_selection", Context.MODE_PRIVATE)
    private val _selectedRoutineId = MutableStateFlow(preferences.getString(KEY, null))
    val selectedRoutineId: StateFlow<String?> = _selectedRoutineId.asStateFlow()

    fun select(routineId: String?) {
        preferences.edit().apply { if (routineId == null) remove(KEY) else putString(KEY, routineId) }.apply()
        _selectedRoutineId.value = routineId
    }

    private companion object { const val KEY = "selected_line_routine_id" }
}
