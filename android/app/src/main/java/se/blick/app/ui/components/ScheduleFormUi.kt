package se.blick.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.blick.app.ui.theme.LocalLightCityTheme
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.StockholmNightSurfaces

/** Shared presentation primitives for the Routine Schedule and one-time event forms. */
@Composable
fun ScheduleSectionCard(
    icon: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable
fun ScheduleValueControl(
    visibleLabel: String,
    value: String,
    accessibilityDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (useStockholmNightSurface) StockholmNightSurfaces.Control else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (useStockholmNightSurface) StockholmNightSurfaces.Border else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .height(80.dp)
            .semantics { contentDescription = accessibilityDescription },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                visibleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun scheduleFormOutlinedTextFieldColors(useLightSurface: Boolean = false): TextFieldColors =
    when {
        LocalStockholmNightTheme.current -> {
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StockholmNightSurfaces.Control,
                unfocusedContainerColor = StockholmNightSurfaces.Control,
                disabledContainerColor = StockholmNightSurfaces.Control,
                errorContainerColor = StockholmNightSurfaces.Control,
                unfocusedBorderColor = StockholmNightSurfaces.Border,
                disabledBorderColor = StockholmNightSurfaces.Border,
            )
        }
        useLightSurface && LocalLightCityTheme.current -> {
            val containerColor = MaterialTheme.colorScheme.surface
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                disabledContainerColor = containerColor,
                errorContainerColor = containerColor,
            )
        }
        else -> OutlinedTextFieldDefaults.colors()
    }
