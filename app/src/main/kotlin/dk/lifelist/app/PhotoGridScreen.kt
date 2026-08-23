package dk.lifelist.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Your camera roll, newest first, several at a time.
 *
 * The system photo picker cannot be pointed at a folder, and DCIM is where the photographs of
 * animals are — everything else in the picker is screenshots, saved memes and receipts. So this
 * is our own grid over MediaStore, filtered to DCIM (see [PhotoLibrary]).
 *
 * Multi-select is the default rather than a long press, because on this screen picking several
 * is the *normal* case: three angles of one moth is one sighting, and requiring a gesture to
 * discover that is how the feature stayed invisible.
 *
 * If the permission is refused the screen does not argue. It says what it cannot do and offers
 * the system picker, which needs no permission and still works — one photograph at a time from
 * everything on the device, which is worse but is not nothing.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PhotoGridScreen(
    onChosen: (List<PhotoLibrary.Item>) -> Unit,
    onUseSystemPicker: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    max: Int = MAX_PHOTOS,
) {
    val context = LocalContext.current
    val permission = rememberPermissionState(PhotoLibrary.PERMISSION)
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }

    // Asked once, on arrival: this screen has one purpose and it needs the answer to do it.
    LaunchedEffect(Unit) {
        if (!permission.status.isGranted) permission.launchPermissionRequest()
    }

    val items by produceState(initialValue = null as List<PhotoLibrary.Item>?, permission.status) {
        value = if (permission.status.isGranted) {
            withContext(Dispatchers.IO) { PhotoLibrary.recent(context) }
        } else {
            null
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            Text(
                "Your photos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onUseSystemPicker) { Text("Browse all") }
        }

        when {
            !permission.status.isGranted -> Refused(onUseSystemPicker)

            items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            items.orEmpty().isEmpty() -> Empty(onUseSystemPicker)

            else -> Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items.orEmpty(), key = { it.id }) { item ->
                        val index = selected.indexOf(item.id)
                        Cell(
                            item = item,
                            order = if (index >= 0) index + 1 else null,
                            // Tapping a full selection does nothing rather than silently
                            // dropping the oldest pick, which reads as the app losing taps.
                            enabled = index >= 0 || selected.size < max,
                            onTap = {
                                selected = if (index >= 0) {
                                    selected - item.id
                                } else {
                                    selected + item.id
                                }
                            },
                        )
                    }
                }

                if (selected.isNotEmpty()) {
                    val chosen = items.orEmpty().filter { it.id in selected }
                    Button(
                        onClick = { onChosen(selected.mapNotNull { id -> chosen.find { it.id == id } }) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(18.dp)
                            .height(52.dp),
                    ) {
                        Text(
                            if (selected.size == 1) "Identify this photo"
                            else "Identify these ${selected.size} photos"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    item: PhotoLibrary.Item,
    order: Int?,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    // Decoded per cell and thrown away with it: a roll is thousands of photographs and holding
    // every thumbnail alive is how a grid runs a phone out of memory.
    val bitmap by produceState(initialValue = null as Bitmap?, item.id) {
        value = withContext(Dispatchers.IO) { PhotoLibrary.thumbnail(context, item) }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onTap)
            .then(
                if (order != null) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
    ) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!enabled && order == null) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
        }
        order?.let {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                // The number, not a tick: with several photographs of one animal the order is
                // the thing worth showing, since the first is the one the list will display.
                Text(
                    "$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Refused(onUseSystemPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Without access to your photos this screen cannot show your camera roll.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onUseSystemPicker) { Text("Choose a photo instead") }
    }
}

@Composable
private fun Empty(onUseSystemPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing in DCIM yet — that is where the camera puts its photographs.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onUseSystemPicker) { Text("Browse everything") }
    }
}
