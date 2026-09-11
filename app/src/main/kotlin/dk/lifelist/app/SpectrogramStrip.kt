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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import dk.lifelist.core.ColumnPacer
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
    /**
     * One column wider than the display.
     *
     * The extra column lives just off the right-hand edge and slides in over the 50 ms it
     * takes the next one to arrive. Without it the picture can only move in whole columns, and
     * a whole column every third frame is a step rather than a scroll.
     */
    private val held = columns + 1
    private val pixels = IntArray(held * bins) { Spectrogram.SILENCE }
    private val pending = ArrayDeque<FloatArray>()
    private val pacer = ColumnPacer(columnNanos = COLUMN_NANOS)

    /**
     * Bumped once per batch of columns, and read inside the draw.
     *
     * Compose observes snapshot reads made while drawing, so touching this in `Canvas` redraws
     * without recomposing anything — which matters at twenty pushes a second on a screen that
     * is also running inference.
     */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Columns as the microphone produces them — in bursts, on the audio thread.
     *
     * They are queued, not drawn. [tick] hands them out at the rate they were recorded at.
     */
    fun offer(fresh: List<FloatArray>) {
        if (fresh.isEmpty()) return
        synchronized(this) {
            for (column in fresh) {
                require(column.size == bins) { "expected $bins rows, got ${column.size}" }
                pending.addLast(column)
            }
            // A queue that grows without bound is a memory leak wearing a spectrogram. More
            // than a screenful waiting means nothing is drawing, and the oldest of it is
            // already off the left-hand edge of anything anyone will see.
            while (pending.size > columns) pending.removeFirst()
            pacer.offer(fresh.size)
        }
    }

    /**
     * One display frame. Returns how far between two columns the picture should sit.
     *
     * Called from `withFrameNanos`, so [now] is the frame clock rather than wall time.
     */
    fun tick(now: Long): Float {
        var revealed = 0
        var phase = 0f
        synchronized(this) {
            val due = pacer.due(now)
            repeat(due) { pending.removeFirstOrNull()?.let { reveal(it); revealed++ } }
            phase = pacer.phase(now)
        }
        if (revealed > 0) version++
        return phase
    }

    /** Show everything waiting, at once. For the moment listening stops. */
    fun drain() {
        var revealed = 0
        synchronized(this) {
            while (pending.isNotEmpty()) {
                reveal(pending.removeFirst())
                revealed++
            }
            pacer.reset()
        }
        if (revealed > 0) version++
    }

    /** Newest column at the right; the picture scrolls left, the way time is usually drawn. */
    private fun reveal(column: FloatArray) {
        for (row in 0 until bins) {
            val base = row * held
            System.arraycopy(pixels, base + 1, pixels, base, held - 1)
            // Row 0 is the top of the picture, so it holds the highest frequency.
            pixels[base + held - 1] = Spectrogram.colour(column[bins - 1 - row])
        }
    }

    fun clear() {
        synchronized(this) {
            pixels.fill(Spectrogram.SILENCE)
            pending.clear()
            pacer.reset()
        }
        version++
    }

    fun copyInto(out: IntArray) {
        synchronized(this) { pixels.copyInto(out) }
    }

    /** Width of the pixel buffer, one more than the display shows. */
    val bufferColumns: Int get() = held

    companion object {
        /** How long one column of audio lasts, in nanoseconds. */
        const val COLUMN_NANOS: Long =
            Spectrogram.HOP * 1_000_000_000L / 32_000L
    }
}

@Composable
fun SpectrogramStrip(
    state: SpectrogramState,
    listening: Boolean,
    elapsedSeconds: Float,
    modifier: Modifier = Modifier,
    /** True while a clip is sounding: the microphone is open but nothing is being identified. */
    muted: Boolean = false,
    height: Dp = 136.dp,
) {
    val bitmap = remember(state) {
        Bitmap.createBitmap(state.bufferColumns, state.bins, Bitmap.Config.ARGB_8888)
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val buffer = remember(state) { IntArray(state.bufferColumns * state.bins) }

    // The display clock. Columns arrive from the microphone in bursts of five, four times a
    // second; they come out of here one at a time, on the frame clock, at the rate they were
    // recorded. `withFrameNanos` rather than a timer because the only clock that matters is
    // the one the screen actually redraws on.
    var phase by remember(state) { mutableFloatStateOf(0f) }
    LaunchedEffect(state, listening) {
        if (!listening) {
            state.drain()
            phase = 0f
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now -> phase = state.tick(now) }
        }
    }

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
            bitmap.setPixels(
                buffer, 0, state.bufferColumns, 0, 0, state.bufferColumns, state.bins,
            )

            // Scaled by a transform rather than drawn into an integer destination rectangle:
            // one column is under two pixels wide on a phone, so rounding the offset to whole
            // pixels would put the step back exactly where the phase was meant to remove it.
            val columnWidth = size.width / state.columns
            withTransform({
                translate(left = -phase * columnWidth)
                scale(
                    scaleX = columnWidth,
                    scaleY = size.height / state.bins,
                    pivot = Offset.Zero,
                )
            }) {
                drawImage(image)
            }
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
            Pulse(listening && !muted)
            Spacer(Modifier.size(7.dp))
            Text(
                when {
                    muted -> "not listening while this plays"
                    listening -> "${elapsedSeconds.roundToInt()}s"
                    else -> "not listening"
                },
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
