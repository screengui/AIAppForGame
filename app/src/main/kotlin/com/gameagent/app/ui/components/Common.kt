package com.gameagent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameagent.app.ui.theme.GameAgentColors
import com.gameagent.core.diagnostics.EventLevel

/** A bordered panel. Every section of the dashboard is one of these. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    status: StatusIndicator? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GameAgentColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, GameAgentColors.Outline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = GameAgentColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            status?.let { StatusPill(it) }
        }
        content()
    }
}

enum class StatusTone { GOOD, WARN, BAD, IDLE }

data class StatusIndicator(val text: String, val tone: StatusTone)

@Composable
fun StatusPill(indicator: StatusIndicator, modifier: Modifier = Modifier) {
    val color = indicator.tone.color()
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(
            text = indicator.text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

fun StatusTone.color(): Color = when (this) {
    StatusTone.GOOD -> GameAgentColors.Success
    StatusTone.WARN -> GameAgentColors.Warning
    StatusTone.BAD -> GameAgentColors.Danger
    StatusTone.IDLE -> GameAgentColors.Neutral
}

/** A label/value row. Values are monospaced so changing numbers do not make the layout jitter. */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: StatusTone? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = GameAgentColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tone?.color() ?: GameAgentColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(color = GameAgentColors.Outline, thickness = 1.dp)
}

/**
 * A small explanatory line.
 *
 * Used to state *limits* next to a feature (single pointer, no release, colour heuristics) rather
 * than hiding them in documentation the user will never read.
 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = GameAgentColors.TextSecondary,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun EventLevelDot(level: EventLevel, modifier: Modifier = Modifier) {
    val color = when (level) {
        EventLevel.ERROR -> GameAgentColors.Danger
        EventLevel.WARN -> GameAgentColors.Warning
        EventLevel.INFO -> GameAgentColors.Accent
        EventLevel.DEBUG -> GameAgentColors.Neutral
    }
    Box(
        modifier
            .size(7.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = "event level $level" },
    )
}
