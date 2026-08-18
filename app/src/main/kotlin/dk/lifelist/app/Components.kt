package dk.lifelist.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dk.lifelist.core.NameRun

/** §1.2 typography: italic runs stay italic, `sp.` and `agg.` stay roman. */
fun List<NameRun>.annotated(): AnnotatedString = buildAnnotatedString {
    this@annotated.forEachIndexed { index, run ->
        if (index > 0) append(" ")
        if (run.italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(run.text) }
        else append(run.text)
    }
}

/** Plain text of a styled name — headlines, content descriptions, share text. */
fun List<NameRun>.plain(): String = joinToString(" ") { it.text }

/**
 * Confidence as a ring.
 *
 * A refusal draws an empty ring and a dash. The returned node is root, whose probability is
 * always 1.0, and "No determination — 100%" is the most misleading thing this screen could say.
 */
@Composable
fun ConfidenceRing(
    fraction: Float?,
    colour: Color,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 58.dp,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        val track = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.size(diameter)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction != null && fraction > 0f) {
                drawArc(
                    color = colour, startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f), useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            if (fraction == null) "—" else "${Math.round(fraction * 100)}%",
            style = MaterialTheme.typography.titleMedium,
            color = if (fraction == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** iNaturalist licences require attribution wherever the photo is shown. */
@Composable
fun CreditLine(credit: String, licence: String, modifier: Modifier = Modifier) {
    Text(
        "Reference photo by $credit · $licence",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** A short all-caps label — the one piece of the field-notebook voice worth keeping. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, colour: Color? = null) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = colour ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
