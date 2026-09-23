package dk.lifelist.app

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Index

/**
 * What there is, with what you have found laid over it.
 *
 * The life list has always been able to say what you have. This is the other half — the thing
 * a paper field guide does by existing, where the species you have not seen are on the page
 * next to the ones you have, and the gap between them is the point.
 *
 * Two levels, because ours cannot be one. A bird guide groups 969 birds into families holding
 * about eleven each and a single scrolling list works. Denmark has **26,722 species in 2,461
 * families**, 39% of them holding one species — a single list would be a header every five
 * rows. So: a group's families here, that family's species behind it (§81).
 */
@Composable
fun IndexScreen(
    group: String,
    lines: List<Index.FamilyLine>,
    /**
     * The group's own standing, passed in rather than summed from [lines].
     *
     * Summing what is on screen was the first version and it is the exact mistake this whole
     * screen exists to stop making: a denominator that describes the rows you happen to be
     * looking at rather than the world. It reads correctly right up until the list is filtered
     * or searched, at which point "4 of 705 birds" quietly becomes "4 of 12".
     */
    standing: Index.GroupLine,
    onOpenFamily: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** A photograph for a family — one of its members, the same one every time. */
    heroFor: (Int) -> Bitmap? = { null },
) {

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { GroupHeading(group, standing.found, standing.total, standing.families) }
            items(lines, key = { it.family.taxonId }) { line ->
                FamilyRow(line, heroFor(line.family.taxonId)) { onOpenFamily(line.family.taxonId) }
            }
        }
    }
}

@Composable
private fun GroupHeading(group: String, found: Int, total: Int, families: Int) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$found",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "of ${total.grouped()} ${group.lowercase()} in Denmark",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Meter(if (total <= 0) 0f else found.toFloat() / total)
        Spacer(Modifier.height(8.dp))
        Text(
            "$families families",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * One family: a face, a name, and how much of it you have.
 *
 * The hero is a photograph of one of its members rather than an illustration, because that is
 * what the app has 3,430 of and buying artwork for 2,461 families is not a thing that happens.
 * A family with nothing photographed gets an empty square rather than a shuffled layout.
 */
@Composable
private fun FamilyRow(line: Index.FamilyLine, hero: Bitmap?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            hero?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                line.family.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                // 1,913 of the 2,461 families have no English name anywhere, so the binomial
                // is the name for most of this list. Set as the name it is rather than upright
                // like a name that failed to load.
                fontStyle = if (line.family.vernacularEn == null) FontStyle.Italic
                else FontStyle.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.family.vernacularEn != null) {
                Text(
                    line.family.scientificName,
                    style = LatinStyle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Meter(line.fraction, height = 5.dp)
        }
        Spacer(Modifier.width(14.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${line.found}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (line.complete) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "of ${line.total}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * A family opened: every Danish species in it, yours first.
 *
 * The rows the app cannot identify are the majority and they are shown the same size as the
 * rest, with one mark to say the camera will not settle this one. Hiding them would put the
 * app's own limits back into the denominator, which is the whole thing this screen exists to
 * stop doing.
 */
@Composable
fun FamilyRoster(
    family: String,
    latin: String,
    members: List<Index.Member>,
    /** Species of this family in Denmark. Not `members.size` — see [IndexScreen]. */
    total: Int,
    identifiable: Int,
    onOpenTaxon: (Int) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailFor: (Int) -> Bitmap? = { null },
) {
    val found = members.count { it.seen }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 14.dp)) {
                    Text(family, style = MaterialTheme.typography.headlineSmall)
                    Text(latin, style = LatinStyle)
                    Spacer(Modifier.height(12.dp))
                    Meter(if (total <= 0) 0f else found.toFloat() / total)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$found of $total in Denmark · " +
                            "the camera can name $identifiable of them",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            items(members, key = { it.species.taxonId }) { member ->
                MemberRow(member, thumbnailFor(member.species.taxonId)) {
                    onOpenTaxon(member.species.taxonId)
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: Index.Member, thumbnail: Bitmap?, onClick: () -> Unit) {
    val species = member.species
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (member.seen) Icons.Filled.Check else Icons.Outlined.Circle,
            contentDescription = if (member.seen) "on your list" else "not found yet",
            tint = if (member.seen) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(11.dp))

        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // A species you have not found yet is drawn quieter than one you have, so the
                // list reads as a list with your part filled in rather than a catalogue.
                .alpha(if (member.seen) 1f else 0.72f)
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                species.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (member.seen) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (species.vernacularEn == null) FontStyle.Italic
                else FontStyle.Normal,
                color = if (member.seen) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (species.vernacularEn != null) {
                Text(
                    species.scientificName,
                    style = LatinStyle.copy(fontSize = 11.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!species.identifiable) {
            // Not a warning, a fact: most of Denmark is outside any model's vocabulary, and a
            // species you have to identify yourself is still a species you can tick.
            Text(
                "by hand",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Meter(fraction: Float, height: Dp = 7.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        // A hair of width at the bottom end, so "1 of 705" is visibly *something* rather than
        // an empty trough that reads as nothing recorded at all.
        val drawn = if (fraction > 0f) fraction.coerceAtLeast(0.015f) else 0f
        Box(
            Modifier
                .fillMaxWidth(drawn)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** Thousands separated, because "10296 insects" is a number nobody reads at a glance. */
internal fun Int.grouped(): String =
    toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * A family's full Danish roster, as a sheet.
 *
 * A sheet rather than a screen because it is a drill-down from the index and the index is where
 * you want to be when you close it — the same shape `TaxonSheet` uses for the same reason.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChecklistFamilySheet(
    family: dk.lifelist.core.ChecklistFamily,
    members: List<Index.Member>,
    onOpenTaxon: (Int) -> Unit,
    onDismiss: () -> Unit,
    thumbnailFor: (Int) -> Bitmap? = { null },
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        FamilyRoster(
            family = family.name,
            latin = family.scientificName,
            members = members,
            total = family.species,
            identifiable = family.identifiable,
            onOpenTaxon = onOpenTaxon,
            thumbnailFor = thumbnailFor,
        )
    }
}
