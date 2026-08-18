package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the viewer is showing. Live bitmaps are already in memory; stored ones are a path. */
sealed interface Viewing {
    val label: String

    data class Live(val bitmap: Bitmap, override val label: String) : Viewing
    data class Stored(val path: String, override val label: String = "Your photo") : Viewing
}

/**
 * A photo, full-screen, at whatever resolution it was captured.
 *
 * The result screen shows a 170dp-tall crop of a 12-megapixel photo, which is enough to
 * recognise that there is an insect and not nearly enough to check whether its wing bars
 * match the reference. Pinch to zoom, drag to pan, double-tap to toggle, tap the X to leave.
 *
 * Deliberately a `Dialog` rather than a route: it is a lightbox over whatever you were
 * looking at, and coming back should not rebuild that screen or lose its scroll position.
 */
@Composable
fun PhotoViewer(viewing: Viewing, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val bitmap by produceState<Bitmap?>(
            initialValue = (viewing as? Viewing.Live)?.bitmap,
            key1 = viewing,
        ) {
            if (viewing is Viewing.Stored) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        File(viewing.path).takeIf { it.exists() }?.let {
                            BitmapFactory.decodeFile(viewing.path)
                        }
                    }.getOrNull()
                }
            }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var zoomed by remember { mutableStateOf(false) }
        val animatedScale by animateFloatAsState(if (zoomed) scale else 1f, label = "scale")

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF14120F))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        zoomed = scale > 1.01f
                        if (zoomed) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoomed = !zoomed
                            scale = if (zoomed) 2.5f else 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = viewing.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = if (zoomed) scale else animatedScale,
                            scaleY = if (zoomed) scale else animatedScale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            }

            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0x66000000),
                    contentColor = Color.White,
                ),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }

            Text(
                viewing.label,
                color = Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }
    }
}
