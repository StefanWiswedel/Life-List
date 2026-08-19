package dk.lifelist.app

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The second and a half between the shutter and the answer.
 *
 * It used to be the word "Identifying…" in a truncated grey label at the bottom of the screen,
 * which on a phone reads as nothing happening. Inference takes about 200 ms per photo and the
 * model load can take several seconds on a cold start, so this window is real and it is the
 * only moment the app asks anyone to wait.
 *
 * Showing the photograph back, with a sweep passing over it, does two things: it confirms the
 * shutter fired — the commonest complaint about a camera that gives no feedback — and it makes
 * the wait feel like work being done on *your* picture rather than a spinner.
 */
@Composable
fun ThinkingScreen(photo: Bitmap?, note: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val offset by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1250),
            repeatMode = RepeatMode.Restart,
        ),
        label = "offset",
    )

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.size(190.dp),
        ) {
            Box {
                photo?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .offsetFraction(offset)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Warm.Rust.copy(alpha = 0.34f),
                                    Color.Transparent,
                                )
                            )
                        )
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("Looking…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slide by a fraction of the parent's height, without measuring it twice. */
private fun Modifier.offsetFraction(fraction: Float): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, (constraints.maxHeight * fraction).toInt())
            }
        }
    )
