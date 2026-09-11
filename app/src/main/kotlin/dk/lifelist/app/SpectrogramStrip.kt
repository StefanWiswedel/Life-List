package dk.lifelist.app

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Spectrogram
import kotlin.math.roundToInt

/**
 * The last ten seconds of sound, as a picture.
 *
 * Asked for directly, and it fixes a real hole: until now the listening screen had a pulsing
 * dot and a counter, so a session that has found nothing in forty seconds looks exactly like a
 * session whose microphone never opened. A spectrogram cannot be faked by a frozen app. It also
 * puts the evidence next to the claim — a chaffinch drawn as a descending cascade, a passing
 * lorry drawn as a smear across the bottom, and the difference visible before the model says a
 * word.
 *
 * The arithmetic is all in `dk.lifelist.core.Spectrogram`, with tests. What is here is a ring of
 * already-coloured pixels and a bitmap to blit them into.
 */
@Stable
class SpectrogramState(
    /** Ten seconds at twenty columns a second. */
    val columns: Int = 200,
    val bins: Int = Spectrogram.BINS,
) {
    private val pixels = IntArray(columns * bins) { Spectrogram.SILENCE }

    /**
     * Bumped once per batch of columns, and read inside the draw.
     *
     * Compose observes snapshot reads made while drawing, so touching this in `Canvas` redraws
     * without recomposing anything — which matters at twenty pushes a second on a screen that
     * is also running inference.
     */
    var version by mutableIntStateOf(0)
        private set

    /** Newest column at the right; the picture scrolls left, the way time is usually drawn. */
    fun push(fresh: List<FloatArray>) {
        if (fresh.isEmpty()) return
        synchronized(this) {
            for (column in fresh) {
                require(column.size == bins) { "expected $bins rows, got ${column.size}" }
                for (row in 0 until bins) {
                    val base = row * columns
                    System.arraycopy(pixels, base + 1, pixels, base, columns - 1)
                    // Row 0 is the top of the picture, so it holds the highest frequency.
                    pixels[base + columns - 1] = Spectrogram.colour(column[bins - 1 - row])
                }
            }
        }
        version++
    }

    fun clear() {
        synchronized(this) { pixels.fill(Spectrogram.SILENCE) }
        version++
    }

    fun copyInto(out: IntArray) {
        synchronized(this) { pixels.copyInto(out) }
    }
}

@Composable
fun SpectrogramStrip(
    state: SpectrogramState,
    listening: Boolean,
    elapsedSeconds: Float,
    modifier: Modifier = Modifier,
    height: Dp = 136.dp,
) {
    val bitmap = remember(state) {
        Bitmap.createBitmap(state.columns, state.bins, Bitmap.Config.ARGB_8888)
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val buffer = remember(state) { IntArray(state.columns * state.bins) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(Spectrogram.SILENCE))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") state.version
            state.copyInto(buffer)
            bitmap.setPixels(buffer, 0, state.columns, 0, 0, state.columns, state.bins)
            drawImage(
                image = image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                // Smoothed, because the picture is 200×64 stretched across a phone. `None`
                // would draw honest 2 mm squares of a thing that has no squares in it.
                filterQuality = FilterQuality.Low,
            )
        }

        // A spectrogram with no frequency axis is decoration. Three labels, derived from the
        // same mel spacing the rows are drawn with rather than typed in beside it.
        Column(
            Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Tick(Spectrogram.hzAt(1f))
            Tick(Spectrogram.hzAt(0.5f))
            Tick(Spectrogram.hzAt(0f))
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pulse(listening)
            Spacer(Modifier.size(7.dp))
            Text(
                if (listening) "${elapsedSeconds.roundToInt()}s" else "not listening",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * An axis label with a shadow under it.
 *
 * Without the shadow the middle label sat on a bright yellow song trace and lost its first
 * character — a legible label most of the time and an unreadable one exactly when the display
 * has something on it.
 */
@Composable
private fun Tick(hz: Float) {
    Text(
        label(hz),
        color = Color.White.copy(alpha = 0.72f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 5f)),
    )
}

internal fun label(hz: Float): String = when {
    hz >= 1_000f -> {
        val tenths = (hz / 100f).roundToInt()
        if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10}.${tenths % 10} kHz"
    }
    else -> "${hz.roundToInt()} Hz"
}

/** The dot that says the microphone is open. Kept here so the strip is the whole indicator. */
@Composable
private fun Pulse(listening: Boolean) {
    val transition = rememberInfiniteTransition(label = "listening")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (listening) Color(0xFFFDE725) else Color.White.copy(alpha = 0.35f))
            .alpha(if (listening) pulse else 1f)
    )
}
