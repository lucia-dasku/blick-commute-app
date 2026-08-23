@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import se.blick.app.R
import se.blick.app.ui.theme.LocalStockholmNightTheme

/**
 * Shared non-step screen header -- `[title]` or `[← title]`, used identically by
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen],
 * [se.blick.app.ui.screens.about.AboutScreen], and the blocked/loading states of
 * [se.blick.app.ui.screens.routinecreate.RoutineCreateScreen]. Never shows a step label or
 * progress bar -- see [BlickWizardHeader] for the routine-creation wizard's own variant of this
 * same visual language (shared [MaterialTheme.typography.titleLarge] title style).
 *
 * [title] is nullable -- null renders just `[←]` with no title text at all, for a screen whose
 * content already identifies itself right below the bar (see
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]'s own call site) and has no
 * need for a second, generic label above it.
 */
@Composable
fun BlickTopBar(
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val useTransparentContainer = LocalStockholmNightTheme.current
    TopAppBar(
        title = {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            // Omitted (rather than disabled) when there's nothing to go back to -- only
            // RoutineListScreen, the app's root screen, ever calls this without onBack.
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = if (useTransparentContainer) {
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            )
        } else {
            TopAppBarDefaults.topAppBarColors()
        },
    )
}
