package dk.lifelist.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Two ways out of the app, at the bottom of anything that describes a taxon.
 *
 * The Google Images one was asked for by name, and the reason given is the useful part:
 * "that's definitely something I do — I look through the Google images, and that kinda helps me
 * verify the identification". An app that answers with a probability and then makes you retype
 * the name somewhere else to check it is an app that does not take verification seriously.
 *
 * The search uses the **scientific** name. A common name pulls in every unrelated thing that
 * shares it — "small tortoiseshell" is a butterfly, a cat coat and a hair comb — and the whole
 * point of the trip is to look at pictures of the right organism.
 */
@Composable
fun SourceLinks(
    scientificName: String,
    articleUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        articleUrl?.let { url ->
            AssistChip(
                onClick = { open(context, url) },
                label = { Text("Wikipedia") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
        AssistChip(
            onClick = { open(context, imageSearch(scientificName)) },
            label = { Text("Image search") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

/** `tbm=isch` is the images tab. Kept here so the one place that builds it is testable by eye. */
fun imageSearch(scientificName: String): String =
    "https://www.google.com/search?tbm=isch&q=" + Uri.encode(scientificName)

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

