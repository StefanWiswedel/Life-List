package dk.lifelist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Families

/**
 * "12 of 310 Geometridae" — one line, and a bar to make it a thing you can fill in.
 *
 * The sentence changes with where the number came from, and that is deliberate rather than
 * untidy. A Danish total is a fact about the country and reads as one; our own vocabulary is a
 * fact about the app and has to admit it, or the reader is quietly told Denmark has 147
 * Geometridae when it has 310.
 */
@Composable
fun FamilyProgressRow(
    progress: Families.Progress,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Species of this family the app can name, or null where the row does not open. */
    members: List<Families.Member>? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onOpenTaxon: (Int) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.vernacularEn ?: progress.scientificName,
                style = if (compact) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (onToggle != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide the species" else "Show the species",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${progress.seen} of ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (progress.complete) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(6.dp))
        Bar(progress.fraction)
        Spacer(Modifier.height(5.dp))

        Text(
            when (progress.source) {
                Families.Source.DENMARK ->
                    "${progress.scientificName} in Denmark"
                // Not a hedge for its own sake: this number moves when the model is retrained,
                // and a reader who thinks it is a fact about Denmark will be quietly misled.
                Families.Source.APP ->
                    "${progress.scientificName} this app can recognise"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        if (expanded && members != null) {
            Spacer(Modifier.height(10.dp))
            Roster(progress, members, onOpenTaxon)
        }
    }
}

/**
 * What is behind the number.
 *
 * "1 of 11 Katydids" is a score. The eleven names are a to-do list, which is the thing a life
 * list is actually for — and the ten you have not found are the half worth reading.
 *
 * A found species is tappable, because the obvious next thought is "which one was that". A
 * missing one is not: there is no record to open, and a row that looks tappable and does
 * nothing is worse than one that plainly is not.
 */
@Composable
private fun Roster(
    progress: Families.Progress,
    members: List<Families.Member>,
    onOpenTaxon: (Int) -> Unit,
) {
    val unnamed = Families.unnamed(progress, members)
    Column(Modifier.padding(start = 2.dp)) {
        for (member in members) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (member.seen) Modifier.clickable { onOpenTaxon(member.taxonId) }
                        else Modifier
                    )
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (member.seen) Icons.Filled.Check else Icons.Outlined.Circle,
                    contentDescription = if (member.seen) "on your list" else "not found yet",
                    tint = if (member.seen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(
                        member.vernacularEn ?: member.scientificName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (member.seen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (member.vernacularEn != null) {
                        Text(
                            member.scientificName,
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // The two denominators are different kinds of thing, and this is where that stops
        // being a footnote: a Red List total is a count with no species list behind it, so a
        // family counted against Denmark can name only what the model was trained on. Listing
        // seven and calling it eight would be the more comfortable lie.
        if (unnamed > 0) {
            Text(
                "and $unnamed more ${progress.scientificName} in Denmark this app cannot name yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        // A hair of width at the bottom end, so "1 of 310" is visibly *something* rather than
        // an empty trough that reads as nothing recorded at all.
        val drawn = if (fraction > 0f) fraction.coerceAtLeast(0.02f) else 0f
        Box(
            Modifier
                .fillMaxWidth(drawn)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** The Red List badge. Only ever drawn for the categories that mean something (§55). */
@Composable
fun RedListBadge(status: RedList.Status, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            status.code,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(0.dp))
        Text(
            status.words,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
