package dk.lifelist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Answer
import dk.lifelist.core.AnswerKind
import dk.lifelist.core.NameRun
import dk.lifelist.core.Presentation

/**
 * The result screen — the one screen this app exists for.
 *
 * Everything shown is computed by `:core`. This file decides nothing about *what* to say;
 * it only lays out an [Answer]. That separation is the point: the wording and the
 * italicisation are tested in `core`, where a test can reach them.
 */

/** §1.2 typography: italic runs stay italic, `sp.` stays roman. */
fun List<NameRun>.annotated(): AnnotatedString = buildAnnotatedString {
    this@annotated.forEachIndexed { index, run ->
        if (index > 0) append(" ")
        if (run.italic) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(run.text) }
        } else {
            append(run.text)
        }
    }
}

@Composable
fun ResultScreen(
    answer: Answer,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Ink.Bone)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        SpecimenLabel(answer)
        Spacer(Modifier.height(22.dp))
        ThresholdControl(threshold, onThresholdChange)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Ink.RuleStrong)
                .clickable(onClick = onRetake)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("TAKE ANOTHER PHOTO", style = Type.field.copy(color = Ink.Rust))
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SpecimenLabel(answer: Answer) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.Surface)
            .border(1.dp, Ink.RuleStrong)
            .padding(20.dp)
    ) {
        Text("DET.", style = Type.field)
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (answer.kind == AnswerKind.UNIDENTIFIED) AnnotatedString("No determination")
                else answer.scientificName.annotated(),
                style = Type.displayName.copy(
                    color = if (answer.kind == AnswerKind.UNIDENTIFIED) Ink.InkSoft else Ink.Ink
                ),
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            RankMark(answer)
        }

        answer.vernacular?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = Type.vernacular)
        }

        Spacer(Modifier.height(16.dp))
        Confidence(answer)

        Spacer(Modifier.height(16.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Sage))
            Spacer(Modifier.width(12.dp))
            Text(answer.explanation, style = Type.body)
        }

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        Text("TAXONOMIC KEY", style = Type.field)
        Spacer(Modifier.height(8.dp))
        answer.lineage.forEachIndexed { depth, step ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    rankAbbreviation(step.rank),
                    style = Type.small.copy(color = if (step.isAnswer) Ink.Rust else Ink.RuleStrong),
                    modifier = Modifier.width(56.dp),
                )
                Spacer(Modifier.width((depth * 8).dp))
                Text(
                    step.name.annotated(),
                    style = Type.body.copy(color = if (step.isAnswer) Ink.Ink else Ink.InkSoft),
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        Text("LEAF CANDIDATES", style = Type.field)
        Spacer(Modifier.height(8.dp))
        answer.candidates.forEach { candidate ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.name.annotated(),
                        style = Type.body.copy(
                            color = if (candidate.withinAnswer) Ink.Ink else Ink.InkSoft
                        ),
                    )
                    // Spec §4.3 — a runner-up outside the returned node is marked, never hidden.
                    if (!candidate.withinAnswer && answer.kind != AnswerKind.UNIDENTIFIED) {
                        Text("OTHER BRANCH", style = Type.field.copy(color = Ink.Ochre))
                    }
                    candidate.vernacular?.let { Text(it, style = Type.small) }
                }
                Text(candidate.confidence.percent, style = Type.small.copy(color = Ink.Ink))
            }
            Hairline()
        }
    }
}

@Composable
private fun RankMark(answer: Answer) {
    val (label, colour) = when (answer.kind) {
        AnswerKind.LEAF -> "SPECIES" to Ink.Sage
        AnswerKind.INDETERMINATE -> "${answer.rankLabel?.uppercase()} · INDET." to Ink.Ochre
        AnswerKind.HIGHER_RANK -> (answer.rankLabel ?: answer.rank).uppercase() to Ink.Ochre
        AnswerKind.UNIDENTIFIED -> "UNRESOLVED" to Ink.InkSoft
    }
    Text(
        label,
        style = Type.field.copy(color = colour),
        modifier = Modifier.border(1.dp, colour).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Confidence(answer: Answer) {
    // A refusal returns root, whose probability is always 1.0. "No determination — 100%" is
    // the most misleading thing this screen could say, so it shows nothing.
    val unresolved = answer.kind == AnswerKind.UNIDENTIFIED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (unresolved) "—" else answer.confidence.percent,
            style = Type.figure.copy(color = if (unresolved) Ink.InkSoft else Ink.Ink),
        )
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(3.dp).background(Ink.Rule)) {
            if (!unresolved) {
                Box(
                    Modifier
                        .fillMaxWidth(answer.confidence.barFraction)
                        .height(3.dp)
                        .background(Ink.Rust)
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (unresolved) "NO NODE CLEARED THE THRESHOLD" else "CONFIDENCE IN THE RETURNED NODE",
        style = Type.field,
    )
}

@Composable
private fun ThresholdControl(threshold: Float, onChange: (Float) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.Surface)
            .border(1.dp, Ink.RuleStrong)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CONFIDENCE THRESHOLD", style = Type.field)
            Text("${Math.round(threshold * 100)}%", style = Type.small.copy(color = Ink.Rust))
        }
        // Spec §4.4 — 0.50 to 0.95, applied at display time so old records re-render honestly.
        Slider(
            value = threshold,
            onValueChange = onChange,
            valueRange = 0.50f..0.95f,
            colors = SliderDefaults.colors(
                thumbColor = Ink.Rust,
                activeTrackColor = Ink.Rust,
                inactiveTrackColor = Ink.Rule,
            ),
        )
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Rule))
}

private fun rankAbbreviation(rank: String) = when (rank) {
    "root" -> "—"
    "kingdom" -> "KGD."
    "phylum" -> "PHY."
    "class" -> "CL."
    "order" -> "ORD."
    "family" -> "FAM."
    "genus" -> "GEN."
    "species" -> "SP."
    "subspecies" -> "SSP."
    else -> rank.uppercase()
}

fun answerFor(probabilities: FloatArray, threshold: Float): Answer =
    Presentation.present(
        Demo.taxonomy,
        dk.lifelist.core.Rollup.rollup(Demo.taxonomy, probabilities, threshold),
    )
