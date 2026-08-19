package dk.lifelist.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.LifeList
import dk.lifelist.core.Presentation
import dk.lifelist.core.Taxon
import dk.lifelist.core.Taxonomy

/**
 * Naming it yourself.
 *
 * Two jobs that look the same and are not. **Settling** picks from the species under a record
 * the app already hedged on — a short, fixed list, and a narrowing. **Correcting** searches the
 * whole taxonomy, because the model returned a confident species and it was simply the wrong
 * moth. Reported twice from real use, with no way in the app to say so.
 *
 * Same list, different source, so they read identically and neither can drift from the other.
 */
@Composable
fun TaxonSearchContent(
    heading: String,
    note: String,
    source: (String) -> List<Taxon>,
    emptyQueryHint: String,
    onBack: (() -> Unit)? = null,
    onPick: (Taxon) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { source(query) }

    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp)) {
        onBack?.let {
            TextButton(
                onClick = it,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Back")
            }
            Spacer(Modifier.height(4.dp))
        }

        Text(heading, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Common or scientific name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.heightIn(max = 430.dp)) {
            items(shown, key = { it.taxonId }) { taxon ->
                Column(Modifier.fillMaxWidth().clickable { onPick(taxon) }) {
                    Row(
                        Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                taxon.vernacularEn ?: taxon.scientificName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (taxon.vernacularEn != null) {
                                Text(
                                    Presentation.styleName(taxon.scientificName, taxon.rank)
                                        .annotated(),
                                    style = LatinStyle.copy(fontSize = 13.sp),
                                )
                            }
                        }
                        // The rank is the whole point when the list mixes species with the
                        // groups above them: "Crambidae" and "Crambus perlellus" are not
                        // alternatives at the same level and should not look like they are.
                        FieldLabel(
                            if (taxon.isLeaf && taxon.taxonId > 0) "species" else taxon.rank,
                            colour = if (taxon.isLeaf && taxon.taxonId > 0)
                                MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (query.trim().length < 2) emptyQueryHint
                        else "Nothing here matches that. This model knows 2,294 Danish " +
                            "species — if it is not one of them, it cannot be recorded by name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                }
            }
        }
    }
}

/** The correction flow, as its own sheet, for use straight off an identification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxonSearchSheet(taxonomy: Taxonomy, onPick: (Taxon) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TaxonSearchContent(
            heading = "What is it, then?",
            note = "Search anything the model knows — species, genus or family. Saved as your " +
                "determination; what the model said stays on the record.",
            source = { LifeList.search(taxonomy, it) },
            emptyQueryHint = "Type at least two letters. A Google Images search from the " +
                "previous screen is often the quickest way to find the name.",
            onPick = onPick,
        )
    }
}
