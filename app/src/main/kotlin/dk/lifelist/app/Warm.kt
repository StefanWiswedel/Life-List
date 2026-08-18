package dk.lifelist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import dk.lifelist.core.AnswerKind

/**
 * The warm direction — `design/result-screen-warm.html`.
 *
 * The specimen-label version was correct and read like a textbook. Everything honest about it
 * survives here; it just stopped leading. The common name is the headline, the Latin sits
 * under it, the taxonomic key and the candidate list wait behind a tap, and the sentence
 * carries the meaning rather than a table of figures.
 */
object Warm {
    val Paper = Color(0xFFFBF7F0)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF2A2621)
    val Soft = Color(0xFF7A736A)
    val Rust = Color(0xFFC2603A)
    val Ochre = Color(0xFFD9A339)
    val Sage = Color(0xFF7C8471)
    val Moss = Color(0xFF4E6151)
    val Line = Color(0xFFEDE6DA)

    val display = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val latin = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Soft)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, color = Ink)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
        letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Soft)
    val figure = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    /** Green when the answer is a species; amber when the app stopped short on purpose. */
    fun ringColour(kind: AnswerKind): Color = when (kind) {
        AnswerKind.LEAF -> Sage
        AnswerKind.INDETERMINATE, AnswerKind.HIGHER_RANK -> Ochre
        AnswerKind.UNIDENTIFIED -> Soft
    }
}

/**
 * Confidence as a ring.
 *
 * A refusal draws an empty ring and a dash. The returned node is root, whose probability is
 * always 1.0, and "No determination — 100%" is the most misleading thing this screen could
 * say (VERIFICATION.md §22 made the same point about the specimen version).
 */
@Composable
fun ConfidenceRing(fraction: Float?, colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(58.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = Warm.Line, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction != null && fraction > 0f) {
                drawArc(
                    color = colour, startAngle = -90f, sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            if (fraction == null) "—" else "${Math.round(fraction * 100)}%",
            style = Warm.figure.copy(color = if (fraction == null) Warm.Soft else Warm.Ink),
        )
    }
}

@Composable
fun Disclosure(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Warm.Line))
        Row(
            Modifier.fillMaxWidth().clickableRow(onToggle).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Warm.body.copy(color = Warm.Moss, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text(if (open) "–" else "+", style = Warm.body.copy(color = Warm.Soft))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { content() }
        }
    }
}

@Composable
fun WarmButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) Warm.Rust else Color(0xFFF4EEE4))
            .clickableRow(onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Warm.body.copy(
                color = if (primary) Color.White else Warm.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            ),
        )
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Warm.Line))
}

@Composable
fun CreditLine(credit: String, licence: String) {
    Text(
        "Reference photo by $credit · $licence",
        style = Warm.label.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** `clickable` without repeating the import at every call site. */
fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
