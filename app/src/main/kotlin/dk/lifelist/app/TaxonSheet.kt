package dk.lifelist.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.OutlinedButton
import dk.lifelist.core.Occurrence
import dk.lifelist.core.Occurrences
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Everything the app can say about one taxon, gathered in one place. */
data class TaxonBrief(
    val taxonId: Int,
    val name: AnnotatedString,
    val vernacular: String?,
    val rank: String,
    val photo: Bitmap?,
    val credit: ReferencePhotos.Credit?,
    val article: Wikipedia.Article?,
    /** A bundled recording of this species, where there is one (§69). */
    val clip: String? = null,
    val clipCredit: ReferenceAudio.Credit? = null,
    /** How often it is recorded in Denmark, and against which family (§72). */
    val occurrence: Occurrence? = null,
    val family: String? = null,
)

/**
 * Tap a name, read about it.
 *
 * Asked for directly: "I should be able to click the title of the other possible species and
 * get an image and/or info". It is also the missing half of the app's own argument — showing
 * that it was choosing between a speckled bush-cricket and a great green bush-cricket is only
 * useful if you can then find out how to tell them apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxonSheet(
    brief: TaxonBrief,
    onOpenPhoto: (Bitmap, String) -> Unit,
    onDismiss: () -> Unit,
    playingClip: String? = null,
    onPlayClip: (String) -> Unit = {},
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            brief.photo?.let { photo ->
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Reference photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onOpenPhoto(photo, "Reference photo") },
                )
                brief.credit?.let { CreditLine(it.credit, it.licence) }
                Spacer(Modifier.height(14.dp))
            }

            brief.clip?.let { clip ->
                OutlinedButton(
                    onClick = { onPlayClip(clip) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    Icon(
                        if (playingClip == clip) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (playingClip == clip) "Stop" else "Hear it")
                }
                brief.clipCredit?.let {
                    Text(
                        "Recording by ${it.credit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            Text(
                brief.vernacular ?: brief.name.text,
                style = MaterialTheme.typography.headlineMedium,
            )
            if (brief.vernacular != null) {
                Text(brief.name, style = LatinStyle)
            }
            Spacer(Modifier.height(4.dp))
            FieldLabel(brief.rank)

            // How often it is recorded here, coloured only when it is worth noticing. A line on
            // every species is noise that teaches the reader to skip the place where the
            // interesting ones appear.
            brief.occurrence?.let { occurrence ->
                Spacer(Modifier.height(8.dp))
                Text(
                    Occurrences.phrase(occurrence, brief.family),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (Occurrences.notable(occurrence)) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            val article = brief.article
            if (article == null) {
                Text(
                    "No Wikipedia article for this one. English Wikipedia covers most Danish " +
                        "birds and mammals and rather fewer of the fungi and micro-moths.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(article.extract, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
                    }
                }) {
                    Text("Read on Wikipedia")
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    "Text from Wikipedia, CC BY-SA 4.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
