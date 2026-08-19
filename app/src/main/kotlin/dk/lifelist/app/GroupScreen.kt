package dk.lifelist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.LifeList
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Taxonomy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One group, opened.
 *
 * The group cards on the home screen were counting correctly and going nowhere — "if I try and
 * click the insects card, nothing happens". A number you cannot open is a scoreboard, and the
 * point of grouping was never the score, it was being able to look at what you have.
 *
 * Sorted newest first, and a record kept at genus sits in the list beside a species-level one
 * from another day rather than in an "unidentified" bucket off to the side (§20). That is the
 * whole data-model argument, made visible.
 */
@Composable
fun GroupScreen(
    taxonomy: Taxonomy,
    label: String,
    records: List<Record>,
    onOpenRecord: (Record) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(records) { records.sortedByDescending { it.observedAt } }
    val species = remember(records) {
        records.count { taxonomy.nodeOrNull(it.taxonId)?.isLeaf == true && it.taxonId > 0 }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp)) {
                Text(label, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${records.map { it.taxonId }.distinct().size} kinds")
                        append(" · ${records.size} ${if (records.size == 1) "sighting" else "sightings"}")
                        val open = records.size - species
                        if (open > 0) append(" · $open still open")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(sorted, key = { it.id }) { record ->
            GroupRow(taxonomy, record) { onOpenRecord(record) }
        }

        if (sorted.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Anything you photograph that lands in $label will " +
                        "show up on this page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupRow(taxonomy: Taxonomy, record: Record, onClick: () -> Unit) {
    val node = taxonomy.nodeOrNull(record.taxonId)
    val styled = remember(record.taxonId) {
        node?.let { Presentation.styleName(it.scientificName, it.rank) }.orEmpty()
    }
    val thumbnail = rememberThumbnail(record.photoPath)
    val isSpecies = node?.isLeaf == true && record.taxonId > 0

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node?.vernacularEn ?: styled.plain().ifBlank { "Not in this model" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (node?.vernacularEn != null) {
                    Text(styled.annotated(), style = LatinStyle.copy(fontSize = 13.sp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        day(record.observedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    record.place?.let {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            Surface(
                color = if (isSpecies) MaterialTheme.colorScheme.secondaryContainer
                else Warm.OchrePale,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (isSpecies) "SPECIES" else (node?.rank ?: "unknown").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSpecies) MaterialTheme.colorScheme.onSecondaryContainer
                    else Warm.RustDeep,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 89.dp),
        )
    }
}

private fun day(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(millis))
