package se.blick.app.ui.screens.routinedetails

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.blick.app.R
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.domain.usecase.effectiveFirstDeparture
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.StockholmNightSurfaces
import se.blick.app.widget.LineBadgeColorMapping
import se.blick.app.widget.toBadgeColor
import java.time.Duration
import java.time.Instant
import java.util.Locale

private val TIMELINE_TIME_COLUMN_WIDTH = 64.dp
private val TIMELINE_RAIL_WIDTH = 28.dp
private val TIMELINE_MARKER_SIZE = 24.dp

/** One role-labelled journey card. Collapsed and expanded states share the same summary/footer;
 * expansion adds the structured timeline between them without changing journey selection. */
@Composable
internal fun JourneyTimelineCard(
    journey: JourneyPlan,
    now: Instant,
    fastestArrival: Instant,
    locale: Locale,
    emphasized: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val timeline = journey.toTimelinePresentation()
    val firstLeg = journey.firstLeg
    val modeLabel = stringResource(firstLeg.transportMode.journeyLabelResId())
    val countdown = formatJourneyDuration(countdownMinutes(now, journey.effectiveFirstDeparture()))
    val arrival = formatDepartureTime(timeline.finalArrivalTime, locale)
    val changeLabel = if (timeline.transferCount == 0) {
        stringResource(R.string.journey_direct)
    } else {
        pluralStringResource(R.plurals.journey_changes, timeline.transferCount, timeline.transferCount)
    }
    val arrivalLabel = stringResource(R.string.journey_arrives, arrival)
    val laterMinutes = Duration.between(fastestArrival, timeline.finalArrivalTime).toMinutes()
    val summaryMetadata = if (journey.role == JourneyRole.PRIMARY) {
        stringResource(R.string.journey_summary_format, changeLabel, arrivalLabel)
    } else {
        stringResource(
            R.string.journey_summary_later_format,
            changeLabel,
            arrivalLabel,
            stringResource(R.string.journey_later, formatJourneyDuration(laterMinutes)),
        )
    }

    Surface(
        color = if (useStockholmNightSurface) StockholmNightSurfaces.Card else MaterialTheme.colorScheme.surface,
        border = if (useStockholmNightSurface) BorderStroke(1.dp, StockholmNightSurfaces.CardBorder) else null,
        tonalElevation = if (useStockholmNightSurface) 0.dp else if (emphasized) 3.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
    ) {
        Column(Modifier.padding(16.dp)) {
            JourneyCardHeader(journey.role, expanded)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                firstLeg.lineDesignation?.takeIf { it.isNotBlank() }?.let {
                    LineBadge(it, firstLeg.transportMode)
                }
                CompactTransportGlyph(firstLeg.transportMode)
                Text(
                    stringResource(
                        R.string.journey_departure_summary,
                        modeLabel,
                        stringResource(R.string.journey_departure_in, countdown),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                summaryMetadata,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(18.dp))
                JourneyTimeline(timeline.items, locale)
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            JourneyDurationFooter(timeline.totalDurationMinutes)
        }
    }
}

/** Planned-event presentation reuses the exact-destination timeline, transport glyphs and line
 * badges, but deliberately uses absolute Stockholm times and planned-only labels. Its collapsed
 * summary keeps the chooser compact; expanding reveals the same timeline used by Routine Details. */
@Composable
internal fun PlannedJourneyTimelineCard(
    journey: JourneyPlan,
    optionLabel: String,
    locale: Locale,
    emphasized: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val timeline = journey.toTimelinePresentation()
    val firstLeg = journey.firstLeg
    val departure = formatDepartureTime(journey.effectiveFirstDeparture(), locale, STOCKHOLM_ZONE)
    val arrival = formatDepartureTime(timeline.finalArrivalTime, locale, STOCKHOLM_ZONE)
    val modeLabel = stringResource(firstLeg.transportMode.journeyLabelResId())
    val changeLabel = if (timeline.transferCount == 0) {
        stringResource(R.string.journey_direct)
    } else {
        pluralStringResource(R.plurals.journey_changes, timeline.transferCount, timeline.transferCount)
    }

    Surface(
        color = if (useStockholmNightSurface) StockholmNightSurfaces.Card else MaterialTheme.colorScheme.surface,
        border = when {
            emphasized -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
            useStockholmNightSurface -> BorderStroke(1.dp, StockholmNightSurfaces.CardBorder)
            else -> null
        },
        tonalElevation = if (useStockholmNightSurface) 0.dp else if (emphasized) 3.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
    ) {
        Column(Modifier.padding(16.dp)) {
            PlannedJourneyCardHeader(optionLabel, expanded, emphasized)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.one_time_event_planned_time_range, departure, arrival),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                firstLeg.lineDesignation?.takeIf { it.isNotBlank() }?.let {
                    LineBadge(it, firstLeg.transportMode)
                }
                CompactTransportGlyph(firstLeg.transportMode)
                Text(
                    firstLeg.lineDesignation?.takeIf { it.isNotBlank() }?.let {
                        stringResource(R.string.journey_mode_line_format, modeLabel, it)
                    } ?: modeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                changeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(18.dp))
                JourneyTimeline(timeline.items, locale, STOCKHOLM_ZONE)
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            JourneyDurationFooter(timeline.totalDurationMinutes)
        }
    }
}

@Composable
private fun JourneyCardHeader(role: JourneyRole, expanded: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                when (role) {
                    JourneyRole.PRIMARY -> R.string.journey_fastest
                    JourneyRole.NEXT -> R.string.journey_next
                    JourneyRole.ALTERNATIVE -> R.string.journey_alternative
                },
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.journey_collapse else R.string.journey_expand),
        )
    }
}

@Composable
private fun PlannedJourneyCardHeader(label: String, expanded: Boolean, emphasized: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.journey_collapse else R.string.journey_expand),
        )
    }
}

@Composable
private fun JourneyTimeline(
    items: List<JourneyTimelineItem>,
    locale: Locale,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            JourneyTimelineRow(
                item = item,
                locale = locale,
                zone = zone,
                first = index == 0,
                last = index == items.lastIndex,
            )
        }
    }
}

@Composable
private fun JourneyTimelineRow(
    item: JourneyTimelineItem,
    locale: Locale,
    zone: java.time.ZoneId,
    first: Boolean,
    last: Boolean,
) {
    val departureTime = when (item) {
        is JourneyTimelineItem.TransitLeg -> item.departureTime
        is JourneyTimelineItem.Walk -> item.departureTime
        is JourneyTimelineItem.Transfer -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = departureTime?.let { formatDepartureTime(it, locale, zone) }.orEmpty(),
            modifier = Modifier.width(TIMELINE_TIME_COLUMN_WIDTH).padding(top = 3.dp, end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        TimelineRail(item, first, last)
        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 42.dp)
                .padding(start = 10.dp, bottom = if (last) 0.dp else 14.dp),
        ) {
            when (item) {
                is JourneyTimelineItem.TransitLeg -> TransitLegContent(item)
                is JourneyTimelineItem.Transfer -> TransferContent(item)
                is JourneyTimelineItem.Walk -> WalkContent(item)
            }
        }
    }
}

@Composable
private fun TransitLegContent(item: JourneyTimelineItem.TransitLeg) {
    val mode = stringResource(item.transportMode.journeyLabelResId())
    Text(
        text = item.lineDesignation?.let { stringResource(R.string.journey_mode_line_format, mode, it) } ?: mode,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        stringResource(R.string.journey_leg_route_format, item.originDisplayName, item.destinationDisplayName),
        style = MaterialTheme.typography.bodyMedium,
    )
    item.direction?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.journey_toward_format, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item.disruptions.forEach {
        Spacer(Modifier.height(3.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TransferContent(item: JourneyTimelineItem.Transfer) {
    Text(
        text = if (item.stationDisplayName.isBlank()) {
            stringResource(R.string.journey_change)
        } else {
            stringResource(R.string.journey_change_at, item.stationDisplayName)
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    item.durationMinutes?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.journey_approximate_duration, formatJourneyDuration(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WalkContent(item: JourneyTimelineItem.Walk) {
    Text(
        text = item.durationMinutes?.let {
            stringResource(R.string.journey_walk_duration, formatJourneyDuration(it))
        } ?: stringResource(R.string.journey_walk),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    if (item.originDisplayName.isNotBlank() || item.destinationDisplayName.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.journey_leg_route_format, item.originDisplayName, item.destinationDisplayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item.disruptions.forEach {
        Spacer(Modifier.height(3.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TimelineRail(item: JourneyTimelineItem, first: Boolean, last: Boolean) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = when (item) {
        is JourneyTimelineItem.TransitLeg -> LineBadgeColorMapping.colorFor(
            item.transportMode,
            item.lineDesignation.orEmpty(),
        ).toBadgeColor()
        is JourneyTimelineItem.Transfer -> MaterialTheme.colorScheme.secondaryContainer
        is JourneyTimelineItem.Walk -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = when (item) {
        is JourneyTimelineItem.Transfer -> MaterialTheme.colorScheme.onSecondaryContainer
        is JourneyTimelineItem.Walk -> MaterialTheme.colorScheme.onTertiaryContainer
        is JourneyTimelineItem.TransitLeg -> Color.White
    }

    Box(Modifier.width(TIMELINE_RAIL_WIDTH).fillMaxHeight()) {
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val markerCenterY = TIMELINE_MARKER_SIZE.toPx() / 2f
            if (!first) drawLine(railColor, Offset(centerX, 0f), Offset(centerX, markerCenterY), 1.dp.toPx())
            if (!last) drawLine(railColor, Offset(centerX, markerCenterY), Offset(centerX, size.height), 1.dp.toPx())
        }
        MarkerGlyph(
            item = item,
            background = markerColor,
            foreground = foreground,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun MarkerGlyph(
    item: JourneyTimelineItem,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(TIMELINE_MARKER_SIZE)) {
        drawCircle(background)
        when (item) {
            is JourneyTimelineItem.TransitLeg -> drawTransportGlyph(item.transportMode, foreground, background)
            is JourneyTimelineItem.Transfer -> drawTransferGlyph(foreground)
            is JourneyTimelineItem.Walk -> drawWalkGlyph(foreground)
        }
    }
}

@Composable
private fun CompactTransportGlyph(mode: TransportMode) {
    val background = MaterialTheme.colorScheme.secondaryContainer
    val foreground = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.size(22.dp)) {
        drawCircle(background)
        drawTransportGlyph(mode, foreground, background)
    }
}

private fun DrawScope.drawTransportGlyph(mode: TransportMode, color: Color, cutout: Color) {
    when (mode) {
        TransportMode.METRO -> drawMetroGlyph(color)
        TransportMode.BUS -> drawBusGlyph(color, cutout)
        TransportMode.TRAIN -> drawTrainGlyph(color, cutout)
        TransportMode.TRAM -> drawTramGlyph(color, cutout)
        TransportMode.FERRY, TransportMode.SHIP -> drawFerryGlyph(color, cutout)
        TransportMode.TAXI, TransportMode.UNKNOWN -> drawGenericTransportGlyph(color, cutout)
    }
}

private fun DrawScope.drawMetroGlyph(color: Color) {
    val unit = size.minDimension / 24f
    drawCircle(
        color = color,
        radius = 7.2f * unit,
        center = Offset(12f * unit, 12f * unit),
        style = Stroke(width = 1.8f * unit),
    )
    drawLine(
        color = color,
        start = Offset(8.5f * unit, 8.8f * unit),
        end = Offset(15.5f * unit, 8.8f * unit),
        strokeWidth = 2f * unit,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(12f * unit, 8.8f * unit),
        end = Offset(12f * unit, 15.8f * unit),
        strokeWidth = 2f * unit,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawBusGlyph(color: Color, cutout: Color) {
    val unit = size.minDimension / 24f
    drawRoundRect(
        color = color,
        topLeft = Offset(6f * unit, 4f * unit),
        size = Size(12f * unit, 14f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f * unit),
    )
    drawRoundRect(
        color = cutout,
        topLeft = Offset(8f * unit, 6.5f * unit),
        size = Size(8f * unit, 5.3f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.1f * unit),
    )
    drawLine(cutout, Offset(12f * unit, 6.7f * unit), Offset(12f * unit, 11.5f * unit), 1.1f * unit)
    drawCircle(cutout, 1.1f * unit, Offset(8.8f * unit, 15f * unit))
    drawCircle(cutout, 1.1f * unit, Offset(15.2f * unit, 15f * unit))
    drawCircle(color, 1.5f * unit, Offset(8.5f * unit, 18.5f * unit))
    drawCircle(color, 1.5f * unit, Offset(15.5f * unit, 18.5f * unit))
}

private fun DrawScope.drawTrainGlyph(color: Color, cutout: Color) {
    val unit = size.minDimension / 24f
    val body = Path().apply {
        moveTo(8f * unit, 4.5f * unit)
        quadraticTo(12f * unit, 2.8f * unit, 16f * unit, 4.5f * unit)
        lineTo(17f * unit, 15f * unit)
        quadraticTo(12f * unit, 19f * unit, 7f * unit, 15f * unit)
        close()
    }
    drawPath(body, color)
    drawRoundRect(
        color = cutout,
        topLeft = Offset(9f * unit, 6f * unit),
        size = Size(6f * unit, 5f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * unit),
    )
    drawLine(cutout, Offset(12f * unit, 6.2f * unit), Offset(12f * unit, 10.8f * unit), 1f * unit)
    drawCircle(cutout, 1f * unit, Offset(9.7f * unit, 14.2f * unit))
    drawCircle(cutout, 1f * unit, Offset(14.3f * unit, 14.2f * unit))
    drawLine(color, Offset(8f * unit, 20f * unit), Offset(11f * unit, 17.2f * unit), 1.3f * unit, StrokeCap.Round)
    drawLine(color, Offset(16f * unit, 20f * unit), Offset(13f * unit, 17.2f * unit), 1.3f * unit, StrokeCap.Round)
}

private fun DrawScope.drawTramGlyph(color: Color, cutout: Color) {
    val unit = size.minDimension / 24f
    drawLine(color, Offset(8.5f * unit, 5f * unit), Offset(12f * unit, 2f * unit), 1.3f * unit, StrokeCap.Round)
    drawLine(color, Offset(12f * unit, 2f * unit), Offset(15.5f * unit, 5f * unit), 1.3f * unit, StrokeCap.Round)
    drawRoundRect(
        color = color,
        topLeft = Offset(6f * unit, 5f * unit),
        size = Size(12f * unit, 13f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f * unit),
    )
    drawRoundRect(
        color = cutout,
        topLeft = Offset(8f * unit, 7f * unit),
        size = Size(8f * unit, 5f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8f * unit),
    )
    drawLine(cutout, Offset(12f * unit, 7.2f * unit), Offset(12f * unit, 11.8f * unit), 1f * unit)
    drawLine(cutout, Offset(7.5f * unit, 14.2f * unit), Offset(16.5f * unit, 14.2f * unit), 1.1f * unit)
    drawCircle(color, 1.4f * unit, Offset(8.5f * unit, 18.6f * unit))
    drawCircle(color, 1.4f * unit, Offset(15.5f * unit, 18.6f * unit))
}

private fun DrawScope.drawFerryGlyph(color: Color, cutout: Color) {
    val unit = size.minDimension / 24f
    drawLine(color, Offset(12f * unit, 3.5f * unit), Offset(12f * unit, 7f * unit), 1.3f * unit, StrokeCap.Round)
    drawRoundRect(
        color = color,
        topLeft = Offset(8f * unit, 6f * unit),
        size = Size(8f * unit, 6f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * unit),
    )
    drawCircle(cutout, 0.9f * unit, Offset(10.2f * unit, 8.7f * unit))
    drawCircle(cutout, 0.9f * unit, Offset(13.8f * unit, 8.7f * unit))
    val hull = Path().apply {
        moveTo(5f * unit, 12f * unit)
        lineTo(19f * unit, 12f * unit)
        lineTo(16.5f * unit, 16f * unit)
        quadraticTo(12f * unit, 18.2f * unit, 7.5f * unit, 16f * unit)
        close()
    }
    drawPath(hull, color)
    drawLine(color, Offset(5f * unit, 19f * unit), Offset(9f * unit, 19f * unit), 1.2f * unit, StrokeCap.Round)
    drawLine(color, Offset(10.5f * unit, 19f * unit), Offset(14.5f * unit, 19f * unit), 1.2f * unit, StrokeCap.Round)
    drawLine(color, Offset(16f * unit, 19f * unit), Offset(19f * unit, 19f * unit), 1.2f * unit, StrokeCap.Round)
}

private fun DrawScope.drawGenericTransportGlyph(color: Color, cutout: Color) {
    val unit = size.minDimension / 24f
    drawRoundRect(
        color = color,
        topLeft = Offset(6f * unit, 6f * unit),
        size = Size(12f * unit, 11f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
    )
    drawRoundRect(
        color = cutout,
        topLeft = Offset(8.5f * unit, 8f * unit),
        size = Size(7f * unit, 4f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * unit),
    )
    drawCircle(color, 1.4f * unit, Offset(9f * unit, 18f * unit))
    drawCircle(color, 1.4f * unit, Offset(15f * unit, 18f * unit))
}

private fun DrawScope.drawTransferGlyph(color: Color) {
    val unit = size.minDimension / 24f
    drawLine(color, Offset(6f * unit, 9f * unit), Offset(17f * unit, 9f * unit), 1.7f * unit, StrokeCap.Round)
    drawLine(color, Offset(14f * unit, 6f * unit), Offset(17f * unit, 9f * unit), 1.7f * unit, StrokeCap.Round)
    drawLine(color, Offset(18f * unit, 15f * unit), Offset(7f * unit, 15f * unit), 1.7f * unit, StrokeCap.Round)
    drawLine(color, Offset(10f * unit, 12f * unit), Offset(7f * unit, 15f * unit), 1.7f * unit, StrokeCap.Round)
}

private fun DrawScope.drawWalkGlyph(color: Color) {
    val unit = size.minDimension / 24f
    drawCircle(color, 2f * unit, Offset(13f * unit, 6f * unit))
    drawLine(color, Offset(12f * unit, 9f * unit), Offset(10f * unit, 14f * unit), 1.8f * unit, StrokeCap.Round)
    drawLine(color, Offset(10f * unit, 14f * unit), Offset(7f * unit, 18f * unit), 1.8f * unit, StrokeCap.Round)
    drawLine(color, Offset(10f * unit, 14f * unit), Offset(14f * unit, 18f * unit), 1.8f * unit, StrokeCap.Round)
    drawLine(color, Offset(11f * unit, 11f * unit), Offset(16f * unit, 12f * unit), 1.8f * unit, StrokeCap.Round)
}

@Composable
private fun JourneyDurationFooter(totalDurationMinutes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_clock),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.journey_total_duration, formatJourneyDuration(totalDurationMinutes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun formatJourneyDuration(minutes: Long): String {
    if (minutes < 60) return stringResource(R.string.journey_duration_minutes, minutes)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0L) {
        stringResource(R.string.journey_duration_hours, hours)
    } else {
        stringResource(R.string.journey_duration_hours_minutes, hours, remainingMinutes)
    }
}
