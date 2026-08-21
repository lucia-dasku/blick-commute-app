package se.blick.app.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.blick.app.R
import se.blick.app.domain.model.RoutineLabel

@StringRes
fun RoutineLabel.stringResourceId(): Int = when (this) {
    RoutineLabel.WORK -> R.string.routine_label_work
    RoutineLabel.HOME -> R.string.routine_label_home
    RoutineLabel.GYM -> R.string.routine_label_gym
    RoutineLabel.STUDY -> R.string.routine_label_study
    RoutineLabel.HOBBY -> R.string.routine_label_hobby
    RoutineLabel.OTHER -> R.string.routine_label_other
}

data class RoutineLabelVisuals(
    @get:DrawableRes val iconResourceId: Int,
    val accent: Color,
    val container: Color,
)

/** Shared icon and color source for every label presentation in the app. */
fun RoutineLabel.visuals(darkTheme: Boolean): RoutineLabelVisuals {
    val iconResourceId = when (this) {
        RoutineLabel.WORK -> R.drawable.ic_label_work
        RoutineLabel.HOME -> R.drawable.ic_label_home
        RoutineLabel.GYM -> R.drawable.ic_label_gym
        RoutineLabel.STUDY -> R.drawable.ic_label_study
        RoutineLabel.HOBBY -> R.drawable.ic_label_hobby
        RoutineLabel.OTHER -> R.drawable.ic_label_other
    }
    val accent = when (this) {
        RoutineLabel.WORK -> if (darkTheme) Color(0xFF9CC2FF) else Color(0xFF2457C5)
        RoutineLabel.HOME -> if (darkTheme) Color(0xFF83D9A8) else Color(0xFF16834B)
        RoutineLabel.GYM -> if (darkTheme) Color(0xFFC4A5FF) else Color(0xFF7040C1)
        RoutineLabel.STUDY -> if (darkTheme) Color(0xFFFFC277) else Color(0xFFC56713)
        RoutineLabel.HOBBY -> if (darkTheme) Color(0xFFFFA6C8) else Color(0xFFB83D73)
        RoutineLabel.OTHER -> if (darkTheme) Color(0xFFC5CAD1) else Color(0xFF58636F)
    }
    return RoutineLabelVisuals(
        iconResourceId = iconResourceId,
        accent = accent,
        container = accent.copy(alpha = if (darkTheme) 0.18f else 0.10f),
    )
}

@Composable
fun RoutineLabelIconContainer(
    label: RoutineLabel,
    modifier: Modifier = Modifier,
    containerSize: Dp = 30.dp,
    iconSize: Dp = 18.dp,
    cornerRadius: Dp = 8.dp,
) {
    val visuals = label.visuals(isSystemInDarkTheme())
    Surface(
        color = visuals.container,
        contentColor = visuals.accent,
        shape = RoundedCornerShape(cornerRadius),
        modifier = modifier
            .size(containerSize)
            .testTag("routine_label_icon_${label.name.lowercase()}"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(visuals.iconResourceId),
                // A localized label always sits beside this decorative icon for TalkBack.
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun RoutineLabelPill(label: RoutineLabel, modifier: Modifier = Modifier) {
    val visuals = label.visuals(isSystemInDarkTheme())
    Surface(
        color = visuals.container,
        contentColor = visuals.accent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.testTag("routine_label_badge_${label.name.lowercase()}"),
    ) {
        Text(
            text = stringResource(label.stringResourceId()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun RoutineLabelBadge(label: RoutineLabel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoutineLabelIconContainer(label)
        RoutineLabelPill(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineLabelSelector(
    selectedLabel: RoutineLabel?,
    onLabelSelected: (RoutineLabel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selectedLabel == null,
            onClick = { onLabelSelected(null) },
            label = { Text(stringResource(R.string.routine_label_none)) },
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedLabel == null,
                selectedBorderColor = MaterialTheme.colorScheme.primary,
                selectedBorderWidth = 2.dp,
            ),
            modifier = Modifier.testTag("routine_label_option_none"),
        )
        RoutineLabel.entries.forEach { label ->
            val visuals = label.visuals(isSystemInDarkTheme())
            FilterChip(
                selected = selectedLabel == label,
                onClick = { onLabelSelected(label) },
                label = { Text(stringResource(label.stringResourceId())) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(visuals.iconResourceId),
                        // The chip's localized text is the accessible name; avoid repeating it.
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = visuals.container,
                    labelColor = visuals.accent,
                    iconColor = visuals.accent,
                    selectedContainerColor = visuals.accent.copy(alpha = 0.18f),
                    selectedLabelColor = visuals.accent,
                    selectedLeadingIconColor = visuals.accent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedLabel == label,
                    selectedBorderColor = visuals.accent,
                    selectedBorderWidth = 2.dp,
                ),
                modifier = Modifier.testTag("routine_label_option_${label.name.lowercase()}"),
            )
        }
    }
}
