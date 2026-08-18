package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Determiner
import dk.lifelist.core.LifeList
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Taxonomy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * My list.
 *
 * Grouped the way Seek groups, because that part of Seek is right: seeing that you have forty
 * insects and no amphibians is what sends you looking for amphibians, and an empty group says
 * more than a hidden one.
 *
 * What is not like Seek: a record kept at genus is here, in its group, counted. Seek shows you
 * a katydid and then will not save it (VERIFICATION.md §19). So the header carries three
 * numbers rather than one, because collapsing them into a single score is exactly how a life
 * list becomes a leaderboard.
 *
 * Each entry opens. A sighting is a *record* — when, where, how sure the model was and which
 * model it was — and a row that shows only a name is a row you cannot check later.
 */
@Composable
fun LifeListScreen(
    taxonomy: Taxonomy,
    records: List<Record>,
    onOpenPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totals = remember(records) { LifeList.totals(taxonomy, records) }
    val tallies = remember(records) { LifeList.tally(taxonomy, records) }
    var openGroups by remember { mutableStateOf(setOf<String>()) }
    var openRecord by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Tally(totals.records.toString(), "records", Modifier.weight(1f))
                        Tally(totals.toSpecies.toString(), "to species", Modifier.weight(1f))
                        Tally(totals.coarser.toString(), "genus or above", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Counted honestly. A record kept at genus is a record — but it is not " +
                            "a species tick either, so it is counted separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        tallies.forEach { tally ->
            val open = tally.label in openGroups
            item(key = "group-${tally.label}") {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                openGroups =
                                    if (open) openGroups - tally.label else openGroups + tally.label
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(tally.label, colour = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${tally.distinctTaxa()}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (tally.records.isEmpty())
                                MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (tally.records.isNotEmpty()) {
                            Icon(
                                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (tally.records.isEmpty()) {
                        Text(
                            "Nothing here yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else if (!open) {
                        Text(
                            summarise(taxonomy, tally.records),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }

            if (open) {
                items(
                    tally.records.sortedByDescending { it.observedAt },
                    key = { it.id },
                ) { record ->
                    RecordRow(
                        taxonomy = taxonomy,
                        record = record,
                        expanded = openRecord == record.id,
                        onToggle = {
                            openRecord = if (openRecord == record.id) null else record.id
                        },
                        onOpenPhoto = onOpenPhoto,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun summarise(taxonomy: Taxonomy, records: List<Record>): String {
    val species = records.count { taxonomy.node(it.taxonId).isLeaf && it.taxonId > 0 }
    val coarser = records.size - species
    return when {
        coarser == 0 -> "$species to species"
        species == 0 -> "$coarser kept at genus or above"
        else -> "$species to species · $coarser at genus or above"
    }
}

@Composable
private fun Tally(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordRow(
    taxonomy: Taxonomy,
    record: Record,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenPhoto: (String) -> Unit,
) {
    val node = taxonomy.node(record.taxonId)
    val styled = Presentation.styleName(node.scientificName, node.rank)
    val thumbnail: Bitmap? = remember(record.photoPath) {
        record.photoPath?.let { path ->
            runCatching {
                if (File(path).exists()) {
                    // A thumbnail, not the photograph: a list of forty full-size bitmaps is
                    // how a gallery screen runs out of memory.
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                } else null
            }.getOrNull()
        }
    }
    val isSpecies = node.isLeaf && record.taxonId > 0

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(
                        record.photoPath?.let { Modifier.clickable { onOpenPhoto(it) } } ?: Modifier
                    )
            ) {
                thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Your photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.vernacularEn ?: styled.plain(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (node.vernacularEn != null) {
                    Text(styled.annotated(), style = LatinStyle.copy(fontSize = 13.sp))
                }
                Text(
                    dateOf(record.observedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                FieldLabel(
                    if (isSpecies) "Species" else node.rank,
                    colour = if (isSpecies) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.tertiary,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 60.dp, bottom = 14.dp)) {
                Detail("When", timeOf(record.observedAt))
                Detail(
                    "Where",
                    if (record.latitude != null && record.longitude != null)
                        Where.format(record.latitude!!, record.longitude!!)
                    else "not recorded",
                )
                Detail(
                    "Determined by",
                    if (record.determinedBy == Determiner.USER) "you"
                    else "the model, ${record.modelVersion}",
                )
                Detail(
                    "Confidence",
                    record.confidence?.let { "${Math.round(it * 100)}% at this rank" }
                        ?: "not recorded",
                )
                Detail(
                    "Committing at",
                    "${Math.round(record.threshold * 100)}% when this was kept",
                )
                if (!isSpecies) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The species is still open. When a better photo settles it, this " +
                            "record can be refined without losing what was originally decided.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun dateOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(millis))

private fun timeOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.UK).format(Date(millis))
