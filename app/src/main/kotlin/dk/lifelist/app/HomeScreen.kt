package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.GroupTally
import dk.lifelist.core.LifeList
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Taxonomy
import java.io.File

/**
 * Home — which is your list.
 *
 * The structural change that everything else hangs off. The app used to open on a viewfinder
 * with the collection filed behind a tab, which made it a classifier that happened to keep
 * notes. Opening on what you have collected makes it a collection you add to, and the camera
 * becomes the one obvious thing to do rather than the only thing on screen.
 *
 * Three numbers at the top, still — a record kept at genus is a record and is also not a
 * species tick, and both have to stay true (§19). What is new is that they are *yours* and
 * they are the first thing you see.
 */
@Composable
fun HomeScreen(
    taxonomy: Taxonomy,
    records: List<Record>,
    onOpenRecord: (Record) -> Unit,
    onOpenGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totals = remember(records) { LifeList.totals(taxonomy, records) }
    val tallies = remember(records) { LifeList.tally(taxonomy, records) }
    val recent = remember(records) { LifeList.recent(records, limit = 10) }
    val (seen, empty) = tallies.partition { it.records.isNotEmpty() }
    // "Nothing yet in … other" is not a gap anyone can go and fill. UNGROUPED exists so a
    // record never falls off the list; it is not a thing to go looking for.
    val unseen = empty.filter { it.label != dk.lifelist.core.UNGROUPED }

    LazyColumn(
        modifier.fillMaxSize(),
        // Room at the bottom for the camera button to float over nothing important.
        contentPadding = PaddingValues(bottom = 132.dp),
    ) {
        item { Hero(totals) }

        if (recent.isNotEmpty()) {
            item { SectionLabel("Recent") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(recent, key = { it.id }) { record ->
                        RecentCard(taxonomy, record) { onOpenRecord(record) }
                    }
                }
            }
        }

        // No label over an empty grid: "YOUR GROUPS" above nothing at all is the first thing
        // a new user reads, and it announces a section that is not there.
        if (seen.isNotEmpty()) item { SectionLabel("Your groups") }

        items(seen.chunked(2)) { pair ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.5.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                pair.forEach { tally ->
                    GroupCard(taxonomy, tally, Modifier.weight(1f)) { onOpenGroup(tally.label) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item {
            if (seen.isEmpty()) {
                EmptyInvitation()
            } else if (unseen.isNotEmpty()) {
                // One quiet line rather than ten rows of "Nothing here yet". The gap is worth
                // naming — it is what sends someone looking for an amphibian — but it does not
                // deserve more of the screen than the things you have actually found.
                Text(
                    "Nothing yet in " + unseen.joinToString(", ") { it.label.lowercase() } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Hero(totals: LifeList.Totals) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
    ) {
        Box {
            // A warm bloom in the corner, so the card reads as paper with light on it rather
            // than as a grey box with numbers in it.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Warm.RustPale, MaterialTheme.colorScheme.surfaceVariant),
                            center = Offset(760f, -80f),
                            radius = 900f,
                        )
                    )
            )
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${totals.taxa}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (totals.taxa == 1) "kind of life on your list" else "kinds of life on your list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("${totals.toSpecies} to species", MaterialTheme.colorScheme.secondary)
                    Chip("${totals.coarser} kept broader", Warm.Amber)
                }
                if (totals.coarser > 0) {
                    Spacer(Modifier.height(13.dp))
                    Text(
                        "${totals.coarser} ${if (totals.coarser == 1) "sighting is" else "sightings are"} " +
                            "held at genus or family — real records, still open. " +
                            "Photograph one again and it may settle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, dot: androidx.compose.ui.graphics.Color) {
    Surface(color = Warm.Card.copy(alpha = 0.72f), shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 26.dp, bottom = 11.dp),
    )
}

@Composable
private fun RecentCard(taxonomy: Taxonomy, record: Record, onClick: () -> Unit) {
    // `nodeOrNull`, not `node`. A record outlives the model that made it, and a retrained
    // taxonomy that has dropped a taxon must not take the whole screen down with it.
    val node = taxonomy.nodeOrNull(record.taxonId)
    val styled = remember(record.taxonId) {
        node?.let { Presentation.styleName(it.scientificName, it.rank) }.orEmpty()
    }
    val thumbnail = rememberThumbnail(record.photoPath)
    val isSpecies = node?.isLeaf == true && record.taxonId > 0

    Column(Modifier.width(116.dp).clickable(onClick = onClick)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
            modifier = Modifier.size(116.dp),
        ) {
            Box {
                thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Surface(
                    color = Warm.Card.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp),
                ) {
                    Text(
                        if (isSpecies) "SPECIES" else (node?.rank ?: "unknown").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSpecies) MaterialTheme.colorScheme.secondary else Warm.Amber,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            node?.vernacularEn ?: styled.plain().ifBlank { "Not in this model" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
        if (node?.vernacularEn != null) {
            Text(
                styled.annotated(),
                style = LatinStyle.copy(fontSize = 11.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupCard(
    taxonomy: Taxonomy,
    tally: GroupTally,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${tally.distinctTaxa()}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(tally.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${tally.records.size} ${if (tally.records.size == 1) "sighting" else "sightings"}" +
                    if (tally.coarser(taxonomy) > 0) " · ${tally.coarser(taxonomy)} open" else "",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EmptyInvitation() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp)) {
        Text(
            "Nothing on your list yet.",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Photograph anything alive and it goes on here — at the rank the evidence " +
                "supports. A ground beetle you cannot name to species is still a record.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Decode a stored photograph small.
 *
 * `inSampleSize = 4` on a 12-megapixel JPEG is a 750 kB bitmap instead of a 48 MB one, and a
 * rail of ten of those at full size is how a list screen runs out of heap on the device it was
 * never tested on.
 */
@Composable
fun rememberThumbnail(path: String?): Bitmap? = remember(path) {
    path?.let {
        runCatching {
            if (File(it).exists()) {
                BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 })
            } else null
        }.getOrNull()
    }
}
