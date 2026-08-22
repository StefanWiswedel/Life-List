package dk.lifelist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Determiner
import dk.lifelist.core.LifeList
import dk.lifelist.core.LocationSource
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Taxonomy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One sighting, opened — and editable.
 *
 * A row that shows only a name is a row you cannot check later. When, where, what the model
 * actually said and which model said it, all stored at the time (§28) rather than recomputed,
 * because a record that silently re-scores itself under a newer model lies about what you were
 * told.
 *
 * And a determination is not final. "If I select genus level but find out more info later, I
 * should be able to select the correct species" — which is exactly the promise the data model
 * has been making since §19: the record holds whatever rank the evidence supported *so far*.
 * Settling it later goes through `LifeList.refine`, which refuses to move sideways or upward
 * and keeps the original in `refinedFrom`. Correcting a record is not the same as erasing what
 * the model said.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(
    taxonomy: Taxonomy,
    record: Record,
    article: Wikipedia.Article?,
    onOpenPhoto: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onRefine: (Int) -> Unit,
    onCorrect: (Int) -> Unit,
    suggestion: Suggestion? = null,
    onUseSuggestion: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var mode by remember(record.id) { mutableStateOf(Mode.DETAILS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (mode) {
            // Narrowing: a short, fixed list of the species under what the app already said.
            Mode.SETTLE -> TaxonSearchContent(
                heading = "Which species?",
                note = "Under ${taxonomy.nodeOrNull(record.taxonId)?.scientificName ?: "this record"}. " +
                    "Saved as your determination — what the model said stays on the record.",
                source = { query ->
                    val all = LifeList.speciesUnder(taxonomy, record.taxonId)
                    if (query.isBlank()) all else all.filter {
                        it.scientificName.contains(query, ignoreCase = true) ||
                            it.vernacularEn?.contains(query, ignoreCase = true) == true
                    }
                },
                emptyQueryHint = "",
                onBack = { mode = Mode.DETAILS },
                onPick = { mode = Mode.DETAILS; onRefine(it.taxonId) },
            )

            // Replacing: the whole taxonomy, because the model can be confidently wrong.
            Mode.CORRECT -> TaxonSearchContent(
                heading = "What is it, then?",
                note = "Search anything the model knows — species, genus or family. Saved as " +
                    "your determination; what the model said stays on the record.",
                source = { LifeList.search(taxonomy, it) },
                emptyQueryHint = "Type at least two letters.",
                onBack = { mode = Mode.DETAILS },
                onPick = { mode = Mode.DETAILS; onCorrect(it.taxonId) },
            )

            Mode.BROADER -> TaxonSearchContent(
                heading = "Keep it broader",
                note = "If you do not believe the species but you are sure of the group, keep " +
                    "it there. That is a real record, and it can still be settled later.",
                source = { LifeList.broader(taxonomy, record.taxonId) },
                emptyQueryHint = "",
                searchable = false,
                onBack = { mode = Mode.DETAILS },
                onPick = { mode = Mode.DETAILS; onCorrect(it.taxonId) },
            )

            Mode.DETAILS -> Details(
                taxonomy = taxonomy,
                record = record,
                article = article,
                suggestion = suggestion,
                onUseSuggestion = onUseSuggestion,
                onDismissSuggestion = onDismissSuggestion,
                onOpenPhoto = onOpenPhoto,
                onAddPhoto = onAddPhoto,
                onSettle = { mode = Mode.SETTLE },
                onCorrect = { mode = Mode.CORRECT },
                onBroaden = { mode = Mode.BROADER },
            )
        }
    }
}

private enum class Mode { DETAILS, SETTLE, CORRECT, BROADER }

@Composable
private fun Details(
    taxonomy: Taxonomy,
    record: Record,
    article: Wikipedia.Article?,
    suggestion: Suggestion?,
    onUseSuggestion: () -> Unit,
    onDismissSuggestion: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onSettle: () -> Unit,
    onCorrect: () -> Unit,
    onBroaden: () -> Unit,
) {
    val context = LocalContext.current
    val node = taxonomy.nodeOrNull(record.taxonId)
    val styled = remember(record.taxonId) {
        node?.let { Presentation.styleName(it.scientificName, it.rank) }.orEmpty()
    }
    val isSpecies = node?.isLeaf == true && record.taxonId > 0
    val settleable = remember(record.taxonId) {
        !isSpecies && LifeList.speciesUnder(taxonomy, record.taxonId).isNotEmpty()
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
    ) {
        if (record.photoPaths.isNotEmpty()) {
            PhotoRow(record.photoPaths, onOpenPhoto)
            Spacer(Modifier.height(16.dp))
        }

        Text(
            node?.vernacularEn ?: styled.plain().ifBlank { "Not in this model" },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (node?.vernacularEn != null) {
            Text(styled.annotated(), style = LatinStyle)
        }

        Spacer(Modifier.height(12.dp))
        RankChip(isSpecies, node?.rank ?: "unknown")

        suggestion?.let { offer ->
            Spacer(Modifier.height(14.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "With ${record.photoPaths.size} photos the model now says " +
                            "${offer.name} — ${offer.percent}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        FilledTonalButton(onClick = onUseSuggestion) { Text("Use it") }
                        TextButton(onClick = onDismissSuggestion) { Text("Keep what I have") }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Detail("When", stamp(record.observedAt))
        WhereRow(record) {
            if (record.latitude != null && record.longitude != null) {
                Where.openInMaps(context, record.latitude!!, record.longitude!!, record.place)
            }
        }
        Detail(
            "Determined by",
            if (record.determinedBy == Determiner.USER) "you" else "the model",
        )
        Detail(
            "Model said",
            record.confidence?.let {
                "${Math.round(it * 100)}% at ${
                    record.refinedFrom?.let { from -> taxonomy.nodeOrNull(from)?.rank }
                        ?: node?.rank ?: "this rank"
                }"
            } ?: "not recorded",
        )
        Detail("Committing at", "${Math.round(record.threshold * 100)}%")
        Detail("Model", record.modelVersion, last = true)

        record.refinedFrom?.let { from ->
            val narrowed = LifeList.wasNarrowed(taxonomy, record)
            val original = taxonomy.nodeOrNull(from)?.scientificName ?: "a coarser rank"
            Spacer(Modifier.height(14.dp))
            Text(
                if (narrowed) "Settled from $original. The original determination is kept, " +
                    "not overwritten."
                else "You corrected this from $original. What the model said is kept on the " +
                    "record rather than replaced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (settleable) {
                FilledTonalButton(onClick = onSettle, modifier = Modifier.weight(1f)) {
                    Text("Settle the species")
                }
            }
            OutlinedButton(onClick = onAddPhoto, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.AddAPhoto, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Add a photo")
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TextButton(onClick = onCorrect, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Search, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Not this? Change it")
            }
            if (LifeList.broader(taxonomy, record.taxonId).isNotEmpty()) {
                TextButton(onClick = onBroaden, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.UnfoldLess, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Keep it broader")
                }
            }
        }

        article?.let {
            Spacer(Modifier.height(20.dp))
            Text(it.extract, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                "From Wikipedia, CC BY-SA 4.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        node?.let {
            Spacer(Modifier.height(14.dp))
            SourceLinks(scientificName = it.scientificName, articleUrl = article?.url)
        }
    }
}

@Composable
private fun PhotoRow(paths: List<String>, onOpenPhoto: (String) -> Unit) {
    if (paths.size == 1) {
        val bitmap = rememberThumbnail(paths.first())
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onOpenPhoto(paths.first()) }
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Your photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paths) { path ->
                val bitmap = rememberThumbnail(path)
                Box(
                    Modifier
                        .size(width = 190.dp, height = 140.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpenPhoto(path) }
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Your photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankChip(isSpecies: Boolean, rank: String) {
    Surface(
        color = if (isSpecies) MaterialTheme.colorScheme.secondaryContainer else Warm.OchrePale,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isSpecies) MaterialTheme.colorScheme.secondary else Warm.Amber)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (isSpecies) "Species" else "$rank level — species still open",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.5.sp,
                color = if (isSpecies) MaterialTheme.colorScheme.onSecondaryContainer
                else Warm.RustDeep,
            )
        }
    }
}

@Composable
private fun WhereRow(record: Record, onOpen: () -> Unit) {
    val hasFix = record.latitude != null && record.longitude != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (hasFix) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Where",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    record.place ?: if (hasFix) "recorded" else "not recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (hasFix) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (hasFix) {
                    // Which of the two claims this is. A photograph carries where it was
                    // taken; the phone only knows where it is now, and presenting the second
                    // as the first is how a moth from last week ends up in the wrong county.
                    val provenance = when (record.locationSource) {
                        LocationSource.PHOTO -> "from the photo"
                        LocationSource.DEVICE -> "from your phone"
                        null -> null
                    }
                    Text(
                        listOfNotNull(
                            Where.format(record.latitude!!, record.longitude!!),
                            provenance,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (hasFix) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = "Open in maps",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Detail(label: String, value: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun stamp(millis: Long): String =
    SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.UK).format(Date(millis))
