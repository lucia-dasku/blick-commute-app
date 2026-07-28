package se.blick.app.ui.screens.routineedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.blick.app.R

/** Placeholder: editing an existing routine's fields is not implemented yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditScreen(routineId: String, onDone: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.routine_edit_title)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("Editing routine $routineId — not yet implemented.")
        }
    }
}
