package dk.lifelist.app

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Answer
import dk.lifelist.core.AnswerKind

/**
 * One of the species the hedge was choosing between, ready to draw.
 *
 * Assembled by the caller because it needs the taxonomy, the reference photos and the
 * probabilities, and this file is not allowed to know about any of those — everything it
 * says still comes from `dk.lifelist.core.Presentation`.
 */
data class Choice(
    val taxonId: Int,
    val name: AnnotatedString,
    val vernacular: String?,
    val percent: String,
    val fraction: Float,
    val photo: Bitmap?,
)

/**
 * The identification, as a moment rather than a document.
 *
 * The old version was correct and read like a form: a small photo strip, a ring, a sentence, a
 * chip and three collapsed grey expanders stacked one on another. Everything honest about it
 * survives; it just stopped competing with the answer. The photograph is the screen, the name
 * sits on it, one sentence says what is being claimed, and every piece of apparatus — the
 * candidate list, the lineage, the numbers — lives behind a single "Why this answer?".
 *
 * The two things that are new rather than moved:
 *
 * - **A first is announced.** A life list is a record of firsts and the app never said so.
 * - **A hedge asks a question.** When the rollup stops above the leaves it now offers the
 *   contenders as photographs to choose between. That turns the app's whole argument from
 *   something you read into something you do, and a choice made here is stored as *yours*
 *   with the model's number kept beside it (§20).
 */
@Composable
fun ResultScreen(
    answer: Answer,
    isFirst: Boolean,
    photos: List<Bitmap>,
    reference: Bitmap?,
    referenceCredit: ReferencePhotos.Credit?,
    article: Wikipedia.Article?,
    choices: List<Choice>,
    picked: Choice?,
    onPick: (Choice) -> Unit,
    onKeep: () -> Unit,
    onAddPhoto: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
    onOpenPhoto: (Bitmap, String) -> Unit,
    onOpenTaxon: (Int) -> Unit,
    kept: Boolean,
    modelNote: String?,
    modifier: Modifier = Modifier,
) {
    var showingReference by remember(answer.taxonId) { mutableStateOf(false) }
    var why by remember(answer.taxonId) { mutableStateOf(false) }

    val heroPhoto = when {
        showingReference && reference != null -> reference
        picked?.photo != null -> picked.photo
        else -> photos.firstOrNull()
    }
    val headline = picked?.vernacular ?: picked?.name?.text ?: headline(answer)
    val latin = picked?.name ?: answer.scientificName.annotated()

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Clears the pinned action bar — 54dp button, 44dp row, gaps and insets. Set
                // by looking at the render: at 140dp the Wikipedia paragraph ended underneath
                // the gradient and read as a rendering fault rather than as more text.
                .padding(bottom = 178.dp)
        ) {
            BoxWithConstraints {
                // A hedge has a question underneath it, so the photograph gives up some height
                // to make sure the question is on screen without scrolling. A confident answer
                // has nothing to ask and can afford to be a picture.
                // Measured against the render rather than guessed. At 1.12 the photograph was
                // beautiful and "Why this answer?" was two scrolls down, which quietly hid the
                // one thing this app exists to show.
                val height = if (choices.isEmpty()) maxWidth * 0.92f else maxWidth * 0.58f
                Box(Modifier.fillMaxWidth().height(height).background(Color.Black)) {
                    heroPhoto?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = if (showingReference) "Reference photo" else "Your photo",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    onOpenPhoto(
                                        it,
                                        if (showingReference) "Reference photo" else "Your photo",
                                    )
                                },
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                0.26f to Color.Transparent,
                                0.48f to Color.Transparent,
                                1f to Color(0xB8140C08),
                            )
                        )
                    )

                    Row(
                        Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.34f),
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.weight(1f))
                        if (reference != null) {
                            YoursOrReference(showingReference) { showingReference = it }
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    }

                    if (showingReference && referenceCredit != null) {
                        Text(
                            "${referenceCredit.credit} · ${referenceCredit.licence}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.66f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        )
                    }

                    Column(
                        Modifier.align(Alignment.BottomStart).padding(16.dp),
                    ) {
                        AnimatedVisibility(
                            visible = isFirst && answer.kind != AnswerKind.UNIDENTIFIED,
                            enter = scaleIn(
                                initialScale = 0.6f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            ),
                        ) {
                            FirstBadge()
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(
                            headline,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        if (latin.isNotEmpty() && latin.text != headline) {
                            Text(
                                latin,
                                style = LatinStyle.copy(
                                    color = Color.White.copy(alpha = 0.86f),
                                    fontSize = 14.5.sp,
                                ),
                            )
                        }
                    }
                }
            }

            Verdict(answer, picked)

            if (choices.isNotEmpty() && picked == null) {
                Chooser(choices, onPick)
            } else if (article != null) {
                Text(
                    article.extract,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp).padding(top = 2.dp),
                )
                Text(
                    "From Wikipedia, CC BY-SA 4.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 18.dp).padding(top = 7.dp),
                )
            }

            Why(answer, article, why, { why = !why }, onOpenTaxon)

            modelNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 18.dp).padding(top = 20.dp),
                )
            }
        }

        Actions(
            keepLabel = when {
                kept -> "In your list"
                picked != null -> "Add ${picked.vernacular ?: picked.name.text}"
                else -> keepLabel(answer)
            },
            keepEnabled = !kept && answer.taxonId != 0,
            kept = kept,
            onKeep = onKeep,
            onAddPhoto = onAddPhoto,
            onRetake = onRetake,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun FirstBadge() {
    Surface(color = Warm.Rust, shape = MaterialTheme.shapes.extraLarge, shadowElevation = 6.dp) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "NEW TO YOUR LIST",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun YoursOrReference(showingReference: Boolean, onChange: (Boolean) -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.34f), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(3.dp)) {
            listOf(false to "Yours", true to "Reference").forEach { (value, label) ->
                val on = showingReference == value
                Surface(
                    onClick = { onChange(value) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (on) Color.White else Color.Transparent,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 12.5.sp,
                        color = if (on) Warm.Ink else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The one sentence about what is being claimed, with the number beside it rather than above. */
@Composable
private fun Verdict(answer: Answer, picked: Choice?) {
    val hedged = picked == null &&
        (answer.kind == AnswerKind.HIGHER_RANK || answer.kind == AnswerKind.INDETERMINATE)

    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(15.dp)) {
            Surface(
                shape = CircleShape,
                color = when {
                    picked != null -> Warm.RustPale
                    hedged -> Warm.OchrePale
                    answer.kind == AnswerKind.UNIDENTIFIED -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            picked != null -> "You"
                            answer.kind == AnswerKind.UNIDENTIFIED -> "—"
                            else -> answer.confidence.percent
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 12.sp,
                        color = when {
                            picked != null -> Warm.RustDeep
                            hedged -> Warm.Amber
                            answer.kind == AnswerKind.UNIDENTIFIED -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Text(
                when {
                    picked != null ->
                        "You called it. Saved as your determination — the model's " +
                            "${picked.percent} is kept beside it, not replaced."
                    else -> answer.explanation
                },
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
            )
        }
    }
}

/**
 * "Which one is it?"
 *
 * The competitors show you a hedge and stop. Seek will not even save it. Offering the
 * contenders as photographs is the difference between being told the app is uncertain and
 * being handed the thing the app is uncertain about — and a naturalist looking at two
 * reference photos beside their own can very often settle it in a second.
 */
@Composable
private fun Chooser(choices: List<Choice>, onPick: (Choice) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Which one is it?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
        Text(
            "All of these are inside what it saw. If you can tell them apart, say so — the " +
                "record is yours, not the model's.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            choices.forEach { choice ->
                Card(
                    Modifier.weight(1f).clickable { onPick(choice) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.25f)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            choice.photo?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Column(Modifier.padding(11.dp)) {
                            Text(
                                choice.vernacular ?: choice.name.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 17.sp,
                            )
                            if (choice.vernacular != null) {
                                Text(choice.name, style = LatinStyle.copy(fontSize = 11.5.sp))
                            }
                            Spacer(Modifier.height(9.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { choice.fraction },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(MaterialTheme.shapes.extraSmall),
                                    color = Warm.Ochre,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    drawStopIndicator = {},
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    choice.percent,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (choices.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Everything the old screen led with, now one tap away and in one place. */
@Composable
private fun Why(
    answer: Answer,
    article: Wikipedia.Article?,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenTaxon: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "Why this answer?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 10.dp)) {
                answer.candidates.forEach { candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTaxon(candidate.taxonId) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.vernacular ?: candidate.name.plain(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (candidate.vernacular != null) {
                                Text(
                                    candidate.name.annotated(),
                                    style = LatinStyle.copy(fontSize = 12.sp),
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { candidate.confidence.barFraction },
                            modifier = Modifier
                                .width(62.dp)
                                .height(4.dp)
                                .clip(MaterialTheme.shapes.extraSmall),
                            color = if (candidate.withinAnswer) MaterialTheme.colorScheme.secondary
                            else Warm.Ochre,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            candidate.confidence.percent,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    answer.lineage.joinToString("  ›  ") { it.name.plain() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )

                if (article != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Tap any name above to read about it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun Actions(
    keepLabel: String,
    keepEnabled: Boolean,
    kept: Boolean,
    onKeep: () -> Unit,
    onAddPhoto: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    0.28f to MaterialTheme.colorScheme.background,
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 22.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Button(
            onClick = onKeep,
            enabled = keepEnabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            if (kept) {
                Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(keepLabel, style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                onClick = onAddPhoto,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.AddAPhoto, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Another photo")
            }
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Start over")
            }
        }
    }
}

/**
 * The headline.
 *
 * A common name when there is one — 3,612 of 4,657 nodes carry one — and the Latin only when
 * it is not already the headline, because printing it twice was the single most-noticed thing
 * wrong with the previous screen.
 */
private fun headline(answer: Answer): String = when {
    answer.kind == AnswerKind.UNIDENTIFIED -> "Not sure enough to say"
    answer.vernacular != null -> answer.vernacular!!
    else -> answer.scientificName.plain()
}

private fun keepLabel(answer: Answer): String = when (answer.kind) {
    AnswerKind.UNIDENTIFIED -> "Keep without a name"
    AnswerKind.LEAF -> "Add to my list"
    else -> "Keep as ${answer.scientificName.plain()}"
}
