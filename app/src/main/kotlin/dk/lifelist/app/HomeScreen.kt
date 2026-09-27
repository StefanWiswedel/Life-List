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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import dk.lifelist.core.Index
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
    /** The species' own picture, for a record that has none of yours. */
    referencePhotoFor: (Int) -> Bitmap? = { null },
    /**
     * What Denmark has in each group, so a card can show the denominator.
     *
     * Empty until the 4.7 MB checklist has parsed, and the cards render without it — the home
     * screen is not allowed to wait on an asset it only decorates with.
     */
    standings: List<Index.GroupLine> = emptyList(),
) {
    val totals = remember(records) { LifeList.totals(taxonomy, records) }
    val tallies = remember(records) { LifeList.tally(taxonomy, records) }
    val recent = remember(records) { LifeList.recent(records, limit = 10) }
    val (seen, empty) = tallies.partition { it.records.isNotEmpty() }
    // "Nothing yet in … other" is not a gap anyone can go and fill. UNGROUPED exists so a
    // record never falls off the list; it is not a thing to go looking for.
    val unseen = empty.filter { it.label != dk.lifelist.core.UNGROUPED }
    val denmark = remember(standings) { standings.associateBy { it.label } }

    /**
     * Every group, yours first and then the ones you have never found anything in.
     *
     * The ones you have not found used to be a single grey sentence — "Nothing yet in
     * amphibians, reptiles, molluscs." That reads as an apology. A card saying *0 of 62* is
     * the same fact and is an invitation, and it is the only route from this screen into
     * Denmark's checklist, which was the point of building the checklist at all.
     */
    val cards = remember(seen, unseen, denmark) {
        // Yours in the order the list already uses. The ones you have never found are sorted
        // *smallest first*, which is the opposite of the rest of this screen and deliberate:
        // 18 amphibians is a summer, 10,296 insects is a life, and the card most worth putting
        // in front of someone is the one they could actually finish.
        seen.map { it to denmark[it.label] } +
            unseen.mapNotNull { tally -> denmark[tally.label]?.let { tally to it } }
                .sortedBy { (_, line) -> line.total }
    }

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
                        RecentCard(taxonomy, record, referencePhotoFor) { onOpenRecord(record) }
                    }
                }
            }
        }

        // No label over an empty grid: a heading above nothing at all is the first thing a
        // new user reads, and it announces a section that is not there.
        if (cards.isNotEmpty()) item { SectionLabel("Denmark, group by group") }

        items(cards.chunked(2)) { pair ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.5.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                pair.forEach { (tally, line) ->
                    GroupCard(taxonomy, tally, line, Modifier.weight(1f)) {
                        onOpenGroup(tally.label)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (seen.isEmpty()) item { EmptyInvitation() }
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
private fun RecentCard(
    taxonomy: Taxonomy,
    record: Record,
    referencePhotoFor: (Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    // `nodeOrNull`, not `node`. A record outlives the model that made it, and a retrained
    // taxonomy that has dropped a taxon must not take the whole screen down with it.
    val node = taxonomy.nodeOrNull(record.taxonId)
    val styled = remember(record.taxonId) {
        node?.let { Presentation.styleName(it.scientificName, it.rank) }.orEmpty()
    }
    val picture = rememberRecordPicture(record, referencePhotoFor)
    val isSpecies = node?.isLeaf == true && record.taxonId > 0

    Column(Modifier.width(116.dp).clickable(onClick = onClick)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
            modifier = Modifier.size(116.dp),
        ) {
            Box {
                picture.bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (picture.isReference) {
                    NotYoursMark(
                        heard = record.clipPath != null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
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

/**
 * The mark on a picture that is not yours.
 *
 * Small and unmissable rather than large and explanatory: on a 116dp tile there is no room for
 * a sentence, and a reference photograph passed off as your own would quietly turn a life list
 * into a field guide. The waveform says why there is no photograph — you heard this one.
 */
@Composable
fun NotYoursMark(heard: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = Warm.Card.copy(alpha = 0.92f),
        shape = CircleShape,
        modifier = modifier.size(21.dp),
    ) {
        Icon(
            if (heard) Icons.Filled.GraphicEq else Icons.Outlined.Image,
            contentDescription = if (heard) {
                "Heard, not photographed — this is a reference picture"
            } else {
                "A reference picture, not your photograph"
            },
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(4.dp),
        )
    }
}

@Composable
private fun GroupCard(
    taxonomy: Taxonomy,
    tally: GroupTally,
    denmark: Index.GroupLine?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // The checklist's own count where there is one, not the tally's.
    //
    // They are different numbers and the difference is the whole argument of this app:
    // `distinctTaxa` counts what is on your list, which includes a bush-cricket you only got
    // to family, and `GroupLine.found` counts Danish *species* you have ticked. Printing the
    // first over the second would read "21 of 10,296" where the 21 and the 10,296 are counting
    // different kinds of thing. The hero says how many are held broader; this says how many
    // are settled.
    val yours = denmark?.found ?: tally.distinctTaxa()
    val found = tally.records.isNotEmpty()
    Card(
        modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (found) 1.dp else 0.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$yours",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (found) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                // The denominator, small and alongside. It is the number that makes 21 mean
                // something: twenty-one insects is a good afternoon or a rounding error
                // depending on what is out there, and the app is the only one that knows.
                denmark?.let {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "of ${thousands(it.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(tally.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (denmark != null) {
                Spacer(Modifier.height(8.dp))
                Bar(denmark.fraction)
                Spacer(Modifier.height(7.dp))
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Text(
                when {
                    !found && denmark != null ->
                        "${thousands(denmark.families)} families to start on"
                    !found -> "nothing yet"
                    else ->
                        "${tally.records.size} ${if (tally.records.size == 1) "sighting" else "sightings"}" +
                            if (tally.coarser(taxonomy) > 0) " · ${tally.coarser(taxonomy)} open" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * How far along this group is.
 *
 * Deliberately not a percentage. 21 of 8,912 is 0.2%, and a label reading "0%" beside a real
 * afternoon's work is a lie about the afternoon rather than a fact about the group. A sliver
 * of colour says the same thing without passing judgement, and it has a floor so a group you
 * have opened at all never draws as empty.
 */
@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Warm.Rust)
            )
        }
    }
}

/** 8912 reads as a serial number; 8,912 reads as a number of species. */
private fun thousands(n: Int): String {
    val digits = n.toString()
    return digits.reversed().chunked(3).joinToString(",").reversed()
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
 * What to draw for a record: your photograph, or the species' own picture.
 *
 * A record made by listening has no photograph — you heard the bird, you did not see it — and
 * the card for one was a blank square with a name under it. The app has a reference photograph
 * of nearly every species it can name, so it can put a face to the name; what it must not do is
 * let that be mistaken for yours, hence [isReference] and the mark the cards draw from it.
 */
data class RecordPicture(val bitmap: Bitmap?, val isReference: Boolean)

@Composable
fun rememberRecordPicture(
    record: Record,
    referenceFor: (Int) -> Bitmap?,
): RecordPicture {
    val own = rememberThumbnail(record.photoPath)
    // Both `remember`s run every time, in the same order. A `remember` behind an `if` moves in
    // the slot table when the condition changes, which is how a composable starts showing
    // another record's photograph.
    val reference = remember(record.taxonId, own) {
        if (own == null) referenceFor(record.taxonId) else null
    }
    return RecordPicture(own ?: reference, own == null && reference != null)
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
