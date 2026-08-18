package dk.lifelist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import dk.lifelist.core.Answer
import dk.lifelist.core.AnswerKind
import dk.lifelist.core.NameRun

/**
 * The result screen, warm direction.
 *
 * Your photo beside a reference, the common name as the headline, one sentence. Nothing
 * honest was removed — the taxonomic key and the full candidate list are a tap away — it
 * simply stopped leading with apparatus. `design/result-screen-warm.html` is the reference.
 *
 * Decides nothing. Everything here comes from `dk.lifelist.core.Presentation`, so there is
 * one implementation of what the app says and it is the tested one.
 */

/** §1.2 typography: italic runs stay italic, `sp.` stays roman. */
fun List<NameRun>.annotated(): AnnotatedString = buildAnnotatedString {
    this@annotated.forEachIndexed { index, run ->
        if (index > 0) append(" ")
        if (run.italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(run.text) }
        else append(run.text)
    }
}

@Composable
fun ResultScreen(
    answer: Answer,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onRetake: () -> Unit,
    onKeep: () -> Unit,
    onOpenList: () -> Unit,
    photo: Bitmap?,
    reference: Bitmap?,
    referenceCredit: ReferencePhotos.Credit?,
    modelNote: String?,
    kept: Boolean,
    modifier: Modifier = Modifier,
) {
    var showCandidates by remember { mutableStateOf(answer.kind != AnswerKind.LEAF) }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(Warm.Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Warm.Card)
        ) {
            PhotoPair(photo, reference)

            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    ConfidenceRing(
                        fraction = if (answer.kind == AnswerKind.UNIDENTIFIED) null
                        else answer.confidence.barFraction,
                        colour = Warm.ringColour(answer.kind),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(headline(answer), style = Warm.display)
                        if (answer.scientificName.isNotEmpty()) {
                            Text(answer.scientificName.annotated(), style = Warm.latin)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(answer.explanation, style = Warm.body)

                referenceCredit?.let {
                    CreditLine(it.credit, it.licence)
                }

                Spacer(Modifier.height(16.dp))

                Disclosure(
                    title = if (answer.kind == AnswerKind.LEAF) "Other possibilities"
                    else "Which one might it be?",
                    open = showCandidates,
                    onToggle = { showCandidates = !showCandidates },
                ) {
                    answer.candidates.forEach { candidate ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.name.annotated(),
                                    style = Warm.latin.copy(
                                        color = if (candidate.withinAnswer) Warm.Ink else Warm.Soft,
                                        fontSize = 15.sp,
                                    ),
                                )
                                candidate.vernacular?.let {
                                    Text(it, style = Warm.body.copy(fontSize = 13.sp, color = Warm.Soft))
                                }
                                if (!candidate.withinAnswer) {
                                    Text(
                                        "OTHER BRANCH",
                                        style = Warm.label.copy(color = Warm.Ochre, fontSize = 10.sp),
                                    )
                                }
                            }
                            Text(
                                candidate.confidence.percent,
                                style = Warm.figure.copy(color = Warm.Soft, fontSize = 14.sp),
                            )
                        }
                        Hairline()
                    }
                }

                Disclosure("Where this sits", showKey, { showKey = !showKey }) {
                    answer.lineage.forEachIndexed { depth, step ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Spacer(Modifier.width((depth * 10).dp))
                            Text(
                                step.name.annotated(),
                                style = Warm.body.copy(
                                    fontSize = 15.sp,
                                    color = if (step.isAnswer) Warm.Ink else Warm.Soft,
                                    fontWeight = if (step.isAnswer) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WarmButton(
                // The button says what will be kept, at the rank it will be kept at. Nobody
                // should have to guess whether "add" means the species or the genus.
                text = if (kept) "Kept" else keepLabel(answer),
                primary = !kept,
                onClick = onKeep,
                modifier = Modifier.weight(1f),
            )
            WarmButton("Retake", primary = false, onClick = onRetake, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        WarmButton("My list", primary = false, onClick = onOpenList, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(18.dp))
        ThresholdControl(threshold, onThresholdChange)

        modelNote?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = Warm.label)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun headline(answer: Answer): String = when {
    answer.kind == AnswerKind.UNIDENTIFIED -> "Not sure enough to say"
    answer.vernacular != null -> answer.vernacular!!
    // No common name — the Latin becomes the headline rather than leaving a blank, but the
    // rank still reads in plain words where there is one.
    answer.rankLabel != null -> "A ${answer.rankLabel}"
    else -> answer.scientificName.joinToString(" ") { it.text }
}

private fun keepLabel(answer: Answer): String = when (answer.kind) {
    AnswerKind.UNIDENTIFIED -> "Keep without a name"
    AnswerKind.LEAF -> "Add to my list"
    else -> "Keep as ${answer.scientificName.joinToString(" ") { it.text }}"
}

@Composable
private fun PhotoPair(photo: Bitmap?, reference: Bitmap?) {
    Row(Modifier.fillMaxWidth().height(170.dp)) {
        PhotoTile(photo, "YOURS", Modifier.weight(1f))
        if (reference != null) {
            Spacer(Modifier.width(2.dp))
            PhotoTile(reference, "REFERENCE", Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhotoTile(bitmap: Bitmap?, label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFFE8E0D2))) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            label,
            style = Warm.label.copy(color = Warm.Ink),
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6FFFFFF))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ThresholdControl(threshold: Float, onChange: (Float) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Warm.Card)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HOW SURE BEFORE IT COMMITS", style = Warm.label)
            Spacer(Modifier.weight(1f))
            Text("${Math.round(threshold * 100)}%", style = Warm.figure.copy(color = Warm.Rust))
        }
        // Spec §4.4 — 0.50 to 0.95, applied at display time, so old records re-render
        // honestly instead of being rewritten.
        Slider(
            value = threshold,
            onValueChange = onChange,
            valueRange = 0.50f..0.95f,
            colors = SliderDefaults.colors(
                thumbColor = Warm.Rust,
                activeTrackColor = Warm.Rust,
                inactiveTrackColor = Warm.Line,
            ),
        )
    }
}
