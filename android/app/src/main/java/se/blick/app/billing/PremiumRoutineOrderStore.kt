package se.blick.app.billing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

interface PremiumRoutineOrderStore {
    val orderedRoutineIds: StateFlow<List<String>>
    fun saveOrder(routineIds: List<String>)
}

object EmptyPremiumRoutineOrderStore : PremiumRoutineOrderStore {
    override val orderedRoutineIds: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override fun saveOrder(routineIds: List<String>) = Unit
}

@Singleton
class PreferencesPremiumRoutineOrderStore @Inject constructor(
    @ApplicationContext context: Context,
) : PremiumRoutineOrderStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _orderedRoutineIds = MutableStateFlow(readOrder())
    override val orderedRoutineIds: StateFlow<List<String>> = _orderedRoutineIds.asStateFlow()

    override fun saveOrder(routineIds: List<String>) {
        val normalized = routineIds.distinct()
        preferences.edit().putString(ORDER_KEY, JSONArray(normalized).toString()).apply()
        _orderedRoutineIds.value = normalized
    }

    private fun readOrder(): List<String> {
        val stored = preferences.getString(ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFERENCES_NAME = "premium_routine_order"
        const val ORDER_KEY = "ordered_routine_ids"
    }
}
