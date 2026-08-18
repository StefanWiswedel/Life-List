package dk.lifelist.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Answer
import dk.lifelist.core.AnswerKind

/**
 * The identification.
 *
 * Decides nothing. Every word comes from `dk.lifelist.core.Presentation`, so there is one
 * implementation of what the app says and it is the tested one. What changed here is only
 * the chrome: Material components instead of hand-rolled boxes, so a button ripples, a
 * card has elevation, and an expander looks like every other expander on the phone.
 */
@Composable
fun ResultScreen(
    answer: Answer,
    threshold: Float,
    onOpenThreshold: () -> Unit,
    onRetake: () -> Unit,
    onAddPhoto: (Bitmap?) -> Unit,
    onKeep: () -> Unit,
    photos: List<Bitmap>,
    reference: Bitmap?,
    referenceCredit: ReferencePhotos.Credit?,
    onOpenPhoto: (Bitmap, String) -> Unit,
    onOpenTaxon: (Int) -> Unit,
    article: Wikipedia.Article?,
    modelNote: String?,
    kept: Boolean,
    modifier: Modifier = Modifier,
) {
    var showCandidates by remember { mutableStateOf(answer.kind != AnswerKind.LEAF) }
    var showKey by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onAddPhoto(runCatching { decodeSoftware(context, it) }.getOrNull()) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            PhotoStrip(photos, reference, onOpenPhoto, onAddPhoto = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })

            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    ConfidenceRing(
                        fraction = if (answer.kind == AnswerKind.UNIDENTIFIED) null
                        else answer.confidence.barFraction,
                        colour = Warm.ringColour(answer.kind),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(headline(answer), style = MaterialTheme.typography.headlineMedium)
                        // Only when it is not already the headline: printing the Latin twice
                        // was the single most-noticed thing wrong with this screen.
                        val latin = answer.scientificName.plain()
                        if (latin.isNotEmpty() && latin != headline(answer)) {
                            Text(answer.scientificName.annotated(), style = LatinStyle)
                        }
                        answer.rankLabel?.let {
                            Spacer(Modifier.height(6.dp))
                            RankBadge(it)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(answer.explanation, style = MaterialTheme.typography.bodyLarge)

                referenceCredit?.let { CreditLine(it.credit, it.licence) }

                Spacer(Modifier.height(14.dp))
                AssistChip(
                    onClick = onOpenThreshold,
                    label = { Text("Committing at ${Math.round(threshold * 100)}%") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))

                if (article != null) {
                    Expander(
                        title = "About ${headline(answer).lowercase().replaceFirstChar { it.uppercase() }}",
                        open = showAbout,
                        onToggle = { showAbout = !showAbout },
                    ) {
                        Text(article.extract, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "From Wikipedia, CC BY-SA 4.0. Tap any name below to read about it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Expander(
                    title = if (answer.kind == AnswerKind.LEAF) "Other possibilities"
                    else "Which one might it be?",
                    open = showCandidates,
                    onToggle = { showCandidates = !showCandidates },
                ) {
                    answer.candidates.forEach { candidate ->
                        CandidateRow(
                            name = candidate.name.annotated(),
                            vernacular = candidate.vernacular,
                            percent = candidate.confidence.percent,
                            fraction = candidate.confidence.barFraction,
                            otherBranch = !candidate.withinAnswer,
                            onClick = { onOpenTaxon(candidate.taxonId) },
                        )
                    }
                }

                Expander("Where this sits", showKey, { showKey = !showKey }) {
                    answer.lineage.forEachIndexed { depth, step ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTaxon(step.taxonId) }
                                .padding(vertical = 4.dp)
                        ) {
                            Spacer(Modifier.width((depth * 12).dp))
                            Text(
                                step.name.annotated(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (step.isAnswer) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (step.isAnswer) FontWeight.SemiBold
                                else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onKeep,
            enabled = !kept && answer.taxonId != 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            if (kept) {
                Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            }
            // The button says what will be kept, at the rank it will be kept at. Nobody
            // should have to guess whether "add" means the species or the genus.
            Text(if (kept) "In your list" else keepLabel(answer))
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    pick.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.AddAPhoto, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add photo")
            }
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Start over")
            }
        }

        modelNote?.let {
            Spacer(Modifier.height(16.dp))
            FieldLabel(it, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The headline.
 *
 * A common name when there is one — 3,612 of 4,657 nodes now carry one, which is the whole
 * of the "it gives the species name twice" bug: the taxonomy asset was built without them,
 * so this fell through to the Latin and the line underneath printed it again.
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

@Composable
private fun RankBadge(rank: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "$rank level",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Your photos, and the reference, side by side and tappable.
 *
 * More than one of yours is normal now: several angles of the same individual are fused
 * before the head sees them (§3.2), which is exactly how a hard insect gets resolved.
 */
@Composable
private fun PhotoStrip(
    photos: List<Bitmap>,
    reference: Bitmap?,
    onOpen: (Bitmap, String) -> Unit,
    onAddPhoto: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .horizontalScrollIfNeeded()
    ) {
        photos.forEachIndexed { index, bitmap ->
            PhotoTile(
                bitmap = bitmap,
                label = if (photos.size == 1) "Yours" else "Yours ${index + 1}",
                modifier = Modifier.weight(1f),
                onClick = { onOpen(bitmap, "Your photo") },
            )
            Spacer(Modifier.width(2.dp))
        }
        if (photos.isEmpty()) {
            PhotoTile(null, "Yours", Modifier.weight(1f), onClick = onAddPhoto)
            Spacer(Modifier.width(2.dp))
        }
        if (reference != null) {
            PhotoTile(
                bitmap = reference,
                label = "Reference",
                modifier = Modifier.weight(1f),
                onClick = { onOpen(reference, "Reference photo") },
            )
        }
    }
}

/** Placeholder for a horizontal scroll once there are more photos than fit. */
private fun Modifier.horizontalScrollIfNeeded(): Modifier = this

@Composable
private fun PhotoTile(
    bitmap: Bitmap?,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Surface(
            color = Color(0xE6FFFFFF),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(8.dp),
        ) {
            FieldLabel(
                label,
                Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                colour = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Expander(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { content() }
        }
    }
}

@Composable
private fun CandidateRow(
    name: androidx.compose.ui.text.AnnotatedString,
    vernacular: String?,
    percent: String,
    fraction: Float,
    otherBranch: Boolean,
    onClick: () -> Unit,
) {
    // Tappable, because showing that it was choosing between a speckled bush-cricket and a
    // great green bush-cricket is only useful if you can then find out how to tell them apart.
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    vernacular ?: name.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (vernacular != null) {
                    Text(name, style = LatinStyle.copy(fontSize = 13.sp))
                }
            }
            Text(percent, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall),
            color = if (otherBranch) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
        )
        if (otherBranch) {
            Spacer(Modifier.height(4.dp))
            FieldLabel("Other branch", colour = MaterialTheme.colorScheme.tertiary)
        }
    }
}
