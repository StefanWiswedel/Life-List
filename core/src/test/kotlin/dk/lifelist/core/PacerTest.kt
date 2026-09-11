package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacerTest {

    private val column = 50_000_000L      // 50 ms, one column of audio
    private val frame = 16_666_667L       // 60 Hz

    /** Run a producer and a consumer against one clock and report when each column appeared. */
    private fun reveals(
        seconds: Double,
        burst: Int,
        burstEvery: Long,
        pacer: ColumnPacer = ColumnPacer(column),
    ): List<Long> {
        val out = mutableListOf<Long>()
        var nextBurst = 0L
        var now = 0L
        val until = (seconds * 1e9).toLong()
        while (now < until) {
            if (now >= nextBurst) {
                pacer.offer(burst)
                nextBurst += burstEvery
            }
            repeat(pacer.due(now)) { out += now }
            now += frame
        }
        return out
    }

    @Test
    fun `a lumpy supply comes out evenly`() {
        // What the microphone actually does: five columns at once, four times a second.
        val at = reveals(seconds = 4.0, burst = 5, burstEvery = 250_000_000L)
        assertTrue(at.size > 60, "only ${at.size} columns in four seconds")

        val gaps = at.zipWithNext { a, b -> b - a }.drop(5)
        val worst = gaps.max()
        // Nominally a column every three frames. Four is the most the stretch band can add,
        // and against a scroll of 36 px a second the difference is under a pixel — the
        // fractional offset carries the eye across it either way.
        assertTrue(
            worst <= 4 * frame + 1,
            "the display stalled for ${worst / 1_000_000} ms",
        )
        // And nothing came out in a clump: the whole point is that a burst of five does not
        // become five columns in one frame.
        assertTrue(gaps.count { it == 0L } == 0, "columns were revealed in the same frame")
    }

    @Test
    fun `the queue stays small`() {
        val pacer = ColumnPacer(column)
        var deepest = 0
        var now = 0L
        var nextBurst = 0L
        while (now < 10_000_000_000L) {
            if (now >= nextBurst) {
                pacer.offer(5)
                nextBurst += 250_000_000L
            }
            pacer.due(now)
            deepest = maxOf(deepest, pacer.queued)
            now += frame
        }
        // A cushion of three, a burst of five: anything past a dozen means it is falling behind
        // and the picture is showing older and older audio.
        assertTrue(deepest <= 12, "the queue reached $deepest columns")
    }

    @Test
    fun `an empty queue reveals nothing and does not run the clock`() {
        val pacer = ColumnPacer(column)
        assertEquals(0, pacer.due(0L))
        assertEquals(0, pacer.due(10_000_000_000L))
        assertEquals(0f, pacer.phase(10_000_000_000L))
    }

    @Test
    fun `a stall is jumped, not paced out`() {
        // The screen was off for two seconds. Forty columns are waiting. Drawing them at
        // twenty a second would show two-second-old audio for the next two seconds.
        val pacer = ColumnPacer(column)
        pacer.offer(40)
        val jumped = pacer.due(0L)
        assertTrue(jumped >= 35, "only $jumped columns were caught up")
        assertTrue(pacer.queued <= 3, "${pacer.queued} left after the jump")
    }

    @Test
    fun `phase runs from zero to one between columns and never past it`() {
        val pacer = ColumnPacer(column)
        pacer.offer(6)
        pacer.due(0L)
        var previous = -1f
        for (step in 0..6) {
            val now = step * (column / 6)
            val phase = pacer.phase(now)
            assertTrue(phase in 0f..1f, "phase $phase at $now")
            assertTrue(phase >= previous, "phase went backwards")
            previous = phase
        }
        // Long past the next column's due time, still clamped: the display may lag, but it
        // must never slide more than one column out of position.
        assertEquals(1f, pacer.phase(column * 4), 0.001f)
    }

    @Test
    fun `resetting forgets the queue`() {
        val pacer = ColumnPacer(column)
        pacer.offer(10)
        pacer.reset()
        assertEquals(0, pacer.queued)
        assertEquals(0, pacer.due(0L))
    }
}
