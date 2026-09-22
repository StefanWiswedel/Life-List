package dk.lifelist.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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

/**
 * Every way a sighting gets onto the list, stacked above the camera.
 *
 * Four now, and they are four because there are four ways a sighting actually happens: you
 * point the phone at something; you come back to a picture you already took; you know what it
 * is and nobody needs to identify anything; and you can hear it but not see it. Only the first
 * two were ever reachable without going *through* the camera, which is a strange thing to make
 * somebody open in order to say "not the camera".
 *
 * Its own composable so it can be rendered. Four stacked buttons is 240dp of right-hand edge
 * and the kind of thing that reads fine in code and looks like a toolbar on a phone — worth a
 * glance before it ships rather than after.
 */
@Composable
fun WaysIn(
    onFromPhotos: () -> Unit,
    onByName: () -> Unit,
    onListen: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        Lesser(Icons.Outlined.PhotoLibrary, "Identify from your photos", onFromPhotos)
        // Between the pictures and the microphone on purpose: the two either side of it hand
        // the job to a model, and this one is the answer to "I already know".
        Lesser(Icons.Outlined.EditNote, "Add a species by name", onByName)
        Lesser(Icons.Outlined.GraphicEq, "Identify a sound", onListen)
        FloatingActionButton(
            onClick = onCamera,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(68.dp),
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "Identify something",
                modifier = Modifier.size(29.dp),
            )
        }
    }
}

@Composable
private fun Lesser(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(52.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
    }
}
