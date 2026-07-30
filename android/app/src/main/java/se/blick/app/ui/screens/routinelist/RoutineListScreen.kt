package se.blick.app.ui.screens.routinelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(
    onAddRoutine: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    viewModel: RoutineListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.routine_list_title)) }) },
        floatingActionButton = {
            // First-beta one-routine limit (see RoutineCreateViewModel's matching save-time
            // guard): hiding the FAB once a routine exists is the primary defence; both
            // read from the same Room-backed repository, so this and the create flow's own
            // check can never disagree about whether a routine already exists.
            if (uiState.routines.isEmpty()) {
                FloatingActionButton(onClick = onAddRoutine) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.routine_list_add))
                }
            }
        },
    ) { paddingValues ->
        if (!uiState.isLoading && uiState.routines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.routine_list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(paddingValues)) {
                items(uiState.routines, key = CommuteRoutine::id) { routine ->
                    ListItem(
                        headlineContent = { Text(routine.name) },
                        supportingContent = { Text(routine.siteName) },
                        // Consistent with the details screen: enabled/disabled is always
                        // stated in words here too, never colour-only (see
                        // RoutineDetailsScreen's statusLabel for the same rule; "paused
                        // today" isn't surfaced here since it requires the injected Clock,
                        // which this list-only screen has no other reason to depend on).
                        trailingContent = if (!routine.enabled) {
                            { Text(stringResource(R.string.routine_details_status_disabled)) }
                        } else null,
                        modifier = Modifier.clickable { onOpenRoutine(routine.id) },
                    )
                }
            }
        }
    }
}
