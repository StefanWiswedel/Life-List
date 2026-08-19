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
    onDismiss: () -> Unit,
) {
    var settling by remember(record.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (settling) {
            SpeciesPicker(
                taxonomy = taxonomy,
                under = record.taxonId,
                onBack = { settling = false },
                onPick = { settling = false; onRefine(it) },
            )
        } else {
            Details(
                taxonomy = taxonomy,
                record = record,
                article = article,
                onOpenPhoto = onOpenPhoto,
                onAddPhoto = onAddPhoto,
                onSettle = { settling = true },
            )
        }
    }
}

@Composable
private fun Details(
    taxonomy: Taxonomy,
    record: Record,
    article: Wikipedia.Article?,
    onOpenPhoto: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onSettle: () -> Unit,
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
            Spacer(Modifier.height(14.dp))
            Text(
                "Settled from ${taxonomy.nodeOrNull(from)?.scientificName ?: "a coarser rank"}. The original " +
                    "determination is kept, not overwritten.",
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
    }
}

/**
 * Choose the species by hand.
 *
 * Not a re-run of the model: the probabilities are long gone by the time someone comes back to
 * a record — only the one number that was true at the time is stored — so this is a choice, and
 * it is recorded as the user's. Alphabetical by common name, because that is how someone scans
 * for a name they have since looked up in a book, and searchable because a genus of Danish
 * micro-moths can run to thirty.
 */
@Composable
private fun SpeciesPicker(
    taxonomy: Taxonomy,
    under: Int,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember(under) { LifeList.speciesUnder(taxonomy, under) }
    val shown = remember(query, all) {
        if (query.isBlank()) all else all.filter {
            it.scientificName.contains(query, ignoreCase = true) ||
                it.vernacularEn?.contains(query, ignoreCase = true) == true
        }
    }

    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Back")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Which species?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Under ${taxonomy.nodeOrNull(under)?.scientificName ?: "this record"}. This is saved as your " +
                "determination — what the model said stays on the record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search ${all.size} species") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(shown, key = { it.taxonId }) { taxon ->
                Column(Modifier.fillMaxWidth().clickable { onPick(taxon.taxonId) }) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            taxon.vernacularEn ?: taxon.scientificName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (taxon.vernacularEn != null) {
                            Text(
                                Presentation.styleName(taxon.scientificName, taxon.rank).annotated(),
                                style = LatinStyle.copy(fontSize = 13.sp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        "No species here match that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
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
                    Text(
                        Where.format(record.latitude!!, record.longitude!!),
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
