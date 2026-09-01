@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.blick.app.R
import se.blick.app.ui.theme.LocalLightCityTheme
import se.blick.app.widget.LINE_BADGE_GREEN

/**
 * Shared routine-creation wizard header -- used identically by all four
 * [se.blick.app.ui.screens.routinecreate.RoutineCreateStep]s: the step's own [title] rendered
 * prominently (same [MaterialTheme.typography.titleLarge] role [BlickTopBar] uses elsewhere),
 * a muted "Step [stepNumber] of [totalSteps]" directly beneath it, and a thin brand-accented
 * [progress] bar spanning the content width right under that.
 *
 * [progress] is computed by and passed in from the caller rather than derived here, so the
 * real `(step.ordinal + 1) / totalSteps` calculation stays exactly where the wizard's other
 * state already lives (`RoutineCreateScreen`) -- this composable only ever renders whatever
 * value it's given, purely presentational.
 *
 * [LINE_BADGE_GREEN] (the same accent [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]
 * already uses for its "Live" status dot) marks completed progress against a muted, low-alpha
 * tint of the theme's own navy `primary` for the track, rather than introducing a new,
 * competing colour constant.
 */
@Composable
fun BlickWizardHeader(
    title: String,
    stepNumber: Int,
    totalSteps: Int,
    progress: Float,
    onBack: () -> Unit,
) {
    val useLightCityCanvas = LocalLightCityTheme.current
    Column(
        Modifier.background(
            if (useLightCityCanvas) Color.Transparent else MaterialTheme.colorScheme.surface,
        ),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.routine_create_step_label, stepNumber, totalSteps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = if (useLightCityCanvas) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                )
            } else {
                TopAppBarDefaults.topAppBarColors()
            },
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .height(4.dp),
            color = LINE_BADGE_GREEN,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            strokeCap = StrokeCap.Round,
            // Empty on purpose -- the default draws a small dot marking the track's end, which
            // reads as a distracting extra "stop" rather than part of a clean progress bar.
            drawStopIndicator = {},
        )
    }
}
