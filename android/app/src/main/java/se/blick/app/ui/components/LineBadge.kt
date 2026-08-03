package se.blick.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.blick.app.domain.model.TransportMode
import se.blick.app.widget.LineBadgeColorMapping
import se.blick.app.widget.toBadgeColor

/**
 * The same small, rounded, colored line-number badge [se.blick.app.widget.BlickRoutineWidget]'s
 * own Glance-based `LineBadge` renders on the home-screen widget — reuses
 * [LineBadgeColorMapping]'s exact SL line-family color mapping and [toBadgeColor]'s exact color
 * values (see that file's own doc), so a line number looks identical wherever it's shown: the
 * widget, route selection, routine setup/editing, the routine list, Routine Details, and
 * departure rows. Not built on Glance (unlike the widget's own version, which is Glance-only and
 * cannot be called from a standard `@Composable`) — this is a plain Jetpack Compose equivalent
 * sharing only the color mapping/values with the widget, not the rendering code itself, since
 * Glance and standard Compose composables are not interchangeable.
 *
 * Always renders — a line/mode combination the mapping doesn't recognize (any mode other than
 * [TransportMode.METRO]/[TransportMode.TRAIN], or a number outside the colored ranges) renders
 * as the same neutral grey badge the widget falls back to, never left uncolored or hidden.
 * White, bold, centered, single-line text on every color for reliable contrast (see
 * `LineBadgeColorMappingTest`'s WCAG AA assertions against these exact color values).
 */
@Composable
fun LineBadge(lineDesignation: String, transportMode: TransportMode, modifier: Modifier = Modifier) {
    val color = LineBadgeColorMapping.colorFor(transportMode, lineDesignation).toBadgeColor()
    Box(
        modifier = modifier
            .background(color = color, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = lineDesignation,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
