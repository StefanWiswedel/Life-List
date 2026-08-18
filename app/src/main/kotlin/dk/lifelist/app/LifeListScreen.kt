package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Grouped the way Seek groups, because that part of Seek is right: seeing that you have
 * forty insects and no amphibians is what sends you looking for amphibians, and an empty
 * group says more than a hidden one.
 *
 * What is not like Seek: a record kept at genus is here, in its group, counted. Seek shows
 * you a katydid and then will not save it (VERIFICATION.md §19). So the header carries three
 * numbers rather than one — records, to species, to genus or coarser — because collapsing
 * them into a single score is exactly how a life list becomes a leaderboard.
 */
@Composable
fun LifeListScreen(
    taxonomy: Taxonomy,
    records: List<Record>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totals = remember(records) { LifeList.totals(taxonomy, records) }
    val tallies = remember(records) { LifeList.tally(taxonomy, records) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier
            .fillMaxSize()
            .background(Warm.Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MY LIST", style = Warm.label.copy(color = Warm.Ink))
            Spacer(Modifier.weight(1f))
            Text(
                "Close",
                style = Warm.body.copy(color = Warm.Rust, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickableRow(onBack),
            )
        }

        Spacer(Modifier.height(14.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFF3EADC))
                .padding(18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tally(totals.records.toString(), "records", Modifier.weight(1f))
                Tally(totals.toSpecies.toString(), "to species", Modifier.weight(1f))
                Tally(totals.coarser.toString(), "to genus or above", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Counted honestly. A record kept at genus is a record — but it is not a " +
                    "species tick either, so it is counted separately.",
                style = Warm.body.copy(fontSize = 13.sp, color = Warm.Soft),
            )
        }

        Spacer(Modifier.height(8.dp))

        tallies.forEach { tally ->
            val open = tally.label in expanded
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickableRow {
                        expanded = if (open) expanded - tally.label else expanded + tally.label
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tally.label.uppercase(), style = Warm.label.copy(color = Warm.Moss))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${tally.distinctTaxa()}",
                        style = Warm.figure.copy(
                            color = if (tally.records.isEmpty()) Warm.Line else Warm.Ink
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Hairline()

                if (tally.records.isEmpty()) {
                    Text(
                        "Nothing here yet.",
                        style = Warm.body.copy(fontSize = 14.sp, color = Warm.Soft),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else if (open) {
                    tally.records.sortedByDescending { it.observedAt }.forEach { record ->
                        RecordRow(taxonomy, record)
                    }
                } else {
                    Text(
                        summarise(taxonomy, tally.records),
                        style = Warm.body.copy(fontSize = 14.sp, color = Warm.Soft),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
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
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB8FFFFFF))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(value, style = Warm.display.copy(fontSize = 24.sp))
        Text(label, style = Warm.label.copy(letterSpacing = 0.sp, fontSize = 11.sp))
    }
}

@Composable
private fun RecordRow(taxonomy: Taxonomy, record: Record) {
    val node = taxonomy.node(record.taxonId)
    val styled = Presentation.styleName(node.scientificName, node.rank)
    val thumbnail: Bitmap? = remember(record.photoPath) {
        record.photoPath?.let { path ->
            runCatching {
                if (File(path).exists()) BitmapFactory.decodeFile(path) else null
            }.getOrNull()
        }
    }
    val isSpecies = node.isLeaf && record.taxonId > 0

    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFE6DCCB))
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.vernacularEn ?: styled.joinToString(" ") { it.text },
                style = Warm.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
            Text(styled.annotated(), style = Warm.latin.copy(fontSize = 13.sp))
            Text(
                buildString {
                    append(dateOf(record.observedAt))
                    if (!isSpecies) append(" · species still open")
                    if (record.determinedBy == dk.lifelist.core.Determiner.USER) {
                        append(" · you identified it")
                    }
                },
                style = Warm.body.copy(fontSize = 12.sp, color = Warm.Soft),
            )
        }
        Text(
            if (isSpecies) "SPECIES" else node.rank.uppercase(),
            style = Warm.label.copy(
                color = if (isSpecies) Warm.Moss else Warm.Ochre,
                fontSize = 10.sp,
            ),
        )
    }
    Hairline()
}

private fun dateOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(millis))
