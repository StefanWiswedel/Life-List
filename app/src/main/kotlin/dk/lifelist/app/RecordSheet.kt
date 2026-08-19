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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import dk.lifelist.core.Determiner
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Taxonomy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One sighting, opened.
 *
 * A row that shows only a name is a row you cannot check later, and a life list whose entries
 * cannot be checked is a scrapbook. When, where, what the model actually said and which model
 * said it — all of it stored at the time (§28) rather than recomputed, because a record that
 * silently re-scores itself under a newer model lies about what you were told.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(
    taxonomy: Taxonomy,
    record: Record,
    article: Wikipedia.Article?,
    onOpenPhoto: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val node = taxonomy.node(record.taxonId)
    val styled = remember(record.taxonId) {
        Presentation.styleName(node.scientificName, node.rank)
    }
    val isSpecies = node.isLeaf && record.taxonId > 0
    val photo = rememberThumbnail(record.photoPath)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 34.dp)
        ) {
            photo?.let { bitmap ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { record.photoPath?.let(onOpenPhoto) }
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Your photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                node.vernacularEn ?: styled.plain(),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (node.vernacularEn != null) {
                Text(styled.annotated(), style = LatinStyle)
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                color = if (isSpecies) MaterialTheme.colorScheme.secondaryContainer
                else Warm.OchrePale,
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
                            .background(
                                if (isSpecies) MaterialTheme.colorScheme.secondary else Warm.Amber
                            )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (isSpecies) "Species" else "${node.rank} level — species still open",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 12.5.sp,
                        color = if (isSpecies) MaterialTheme.colorScheme.onSecondaryContainer
                        else Warm.RustDeep,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Detail("When", stamp(record.observedAt))
            Detail(
                "Where",
                if (record.latitude != null && record.longitude != null)
                    Where.format(record.latitude!!, record.longitude!!)
                else "not recorded",
            )
            Detail(
                "Determined by",
                if (record.determinedBy == Determiner.USER) "you" else "the model",
            )
            Detail(
                "Model said",
                record.confidence?.let {
                    "${Math.round(it * 100)}% at ${if (isSpecies) "species" else node.rank}"
                } ?: "not recorded",
            )
            Detail("Committing at", "${Math.round(record.threshold * 100)}%")
            Detail("Model", record.modelVersion, last = true)

            record.refinedFrom?.let { from ->
                Spacer(Modifier.height(14.dp))
                Text(
                    "Refined from ${taxonomy.node(from).scientificName}. The original " +
                        "determination is kept, not overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            article?.let {
                Spacer(Modifier.height(18.dp))
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
