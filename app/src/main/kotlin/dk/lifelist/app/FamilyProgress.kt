package dk.lifelist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.vernacularEn ?: progress.scientificName,
                style = if (compact) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
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
