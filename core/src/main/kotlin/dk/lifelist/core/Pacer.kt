package dk.lifelist.core

/**
 * Turning a lumpy supply of columns into an even scroll.
 *
 * The microphone does not deliver audio at a steady rate; it delivers a bufferful whenever it
 * has one, which at the sizes Android picks is about four times a second. Five columns of
 * spectrogram then arrive at once and the display lurches five columns wide, four times a
 * second, which is exactly what it looks like.
 *
 * So the columns are not drawn when they arrive. They queue, and a clock hands them out at the
 * rate the audio was recorded at — the same idea as the jitter buffer in any streaming player,
 * and it costs the same thing: a fixed lag of [cushion] columns, here about 150 ms, which
 * nobody watching a spectrogram can perceive and which buys a display that never stutters.
 *
 * Pure, and therefore testable: given a list of arrival times and a list of frame times, the
 * reveal times are a function, and a test can assert they are evenly spaced.
 */
class ColumnPacer(
    /** How long one column of audio lasts. */
    private val columnNanos: Long = 50_000_000L,
    /** How many columns to keep in hand, so a late buffer does not empty the queue. */
    private val cushion: Int = 3,
    /** More than this many waiting means something stalled; give up pacing and catch up. */
    private val backlog: Int = 24,
) {
    init {
        require(columnNanos > 0) { "columnNanos must be positive, got $columnNanos" }
        require(cushion >= 0) { "cushion must not be negative, got $cushion" }
    }

    private var waiting = 0
    private var started = false
    private var dueAt = 0L
    private var interval = columnNanos

    /** Columns that have arrived and are not yet shown. */
    val queued: Int get() = waiting

    fun offer(count: Int) {
        require(count >= 0) { "count must not be negative, got $count" }
        waiting += count
    }

    fun reset() {
        waiting = 0
        started = false
        interval = columnNanos
    }

    /**
     * How many columns to reveal at [now].
     *
     * Called once a frame. Usually 0 or 1 — at twenty columns a second against sixty frames,
     * two frames in three have nothing to do.
     */
    fun due(now: Long): Int {
        if (waiting == 0) return 0
        if (!started) {
            started = true
            dueAt = now
        }

        // A stall — the screen was off, or inference blocked the thread. Pacing a backlog out
        // at 20 a second would show audio that is three seconds old and getting older. Jump.
        if (waiting > backlog) {
            val jump = waiting - cushion
            waiting = cushion
            dueAt = now + interval
            return jump
        }

        var revealed = 0
        while (waiting > 0 && now >= dueAt) {
            waiting--
            revealed++
            interval = intervalFor(waiting)
            dueAt += interval
        }
        return revealed
    }

    /**
     * How far between the last column and the next one [now] is, in 0..1.
     *
     * The display is shifted by this fraction of a column, so a column slides on rather than
     * appearing. Without it the scroll is still even but still visibly stepped, because one
     * column lasts three frames at 60 Hz.
     */
    fun phase(now: Long): Float {
        if (!started || waiting == 0) return 0f
        val since = now - (dueAt - interval)
        return (since.toFloat() / interval).coerceIn(0f, 1f)
    }

    /**
     * Speed up when the queue is long, stretch when it is short.
     *
     * Deliberately three coarse bands rather than a controller with a gain: the thing being
     * controlled is a 50 ms tick, the measurement is an integer queue length, and a smooth
     * controller on those inputs is a way to write an oscillator by accident.
     *
     * Gentle bands, too. The first pair were 0.6x and 1.4x, and against a supply that is
     * already the right rate on average they made the queue swing through the stretch band
     * every burst, which put an 83 ms gap in a 50 ms scroll — correcting an error that was not
     * there. A jitter buffer should barely move.
     */
    private fun intervalFor(remaining: Int): Long = when {
        remaining > cushion * 2 -> columnNanos * 4 / 5
        remaining < cushion -> columnNanos * 6 / 5
        else -> columnNanos
    }
}
