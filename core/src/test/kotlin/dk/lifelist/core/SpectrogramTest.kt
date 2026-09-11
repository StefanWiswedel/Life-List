package dk.lifelist.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The display is the one part of the audio path with no golden fixture behind it — it is ours,
 * not the model's — so it gets tested against the only thing that can settle it: signals whose
 * answer is known before the code runs.
 */
class SpectrogramTest {

    private val rate = 32_000

    /** A tone in a room. Real audio always has a floor; a mathematically pure tone has none,
     *  and a display tuned against one would be tuned against a signal that cannot occur. */
    private fun tone(hz: Float, seconds: Float, amplitude: Float = 1f, room: Float = 0.0005f): FloatArray {
        val n = (rate * seconds).toInt()
        val noise = Random(11)
        return FloatArray(n) {
            amplitude * sin(2.0 * PI * hz * it / rate).toFloat() +
                noise.nextDouble(-1.0, 1.0).toFloat() * room
        }
    }

    private fun room(seconds: Float, level: Float = 0.0005f): FloatArray {
        val noise = Random(23)
        return FloatArray((rate * seconds).toInt()) {
            noise.nextDouble(-1.0, 1.0).toFloat() * level
        }
    }

    private fun loudestRow(column: FloatArray): Int =
        column.indices.maxByOrNull { column[it] }!!

    @Test
    fun `a tone lands in the row that covers it`() {
        val graph = Spectrograph(sampleRate = rate)
        val edges = Spectrogram.edges(Spectrogram.BINS, Spectrogram.FFT_SIZE, rate)
        val perBin = rate.toFloat() / Spectrogram.FFT_SIZE

        for (hz in listOf(500f, 1_500f, 4_000f, 9_000f)) {
            graph.reset()
            val column = graph.add(tone(hz, 0.5f)).last()
            val row = loudestRow(column)
            val from = edges[row] * perBin
            val to = edges[row + 1] * perBin
            assertTrue(
                hz >= from - perBin && hz <= to + perBin,
                "$hz Hz lit row $row, which covers $from..$to Hz",
            )
        }
    }

    @Test
    fun `a full-scale tone is at the top of the scale and its neighbours are not`() {
        val graph = Spectrograph(sampleRate = rate)
        val column = graph.add(tone(2_000f, 0.5f)).last()
        val row = loudestRow(column)
        assertEquals(1f, column[row], 0.001f)

        // A picture where a single tone lights half the rows is a picture of nothing.
        val lit = column.count { it > 0.5f }
        assertTrue(lit <= 3, "one tone lit $lit of ${column.size} rows")
    }

    @Test
    fun `silence is drawn as silence`() {
        val graph = Spectrograph(sampleRate = rate)
        val columns = graph.add(FloatArray(rate)) // one second of nothing at all
        assertTrue(columns.isNotEmpty())
        for (column in columns) {
            assertTrue(column.all { it < 0.01f }, "silence lit a row: ${column.max()}")
        }
    }

    @Test
    fun `an empty room is drawn as an empty room, however loud its hiss`() {
        // The point of a floor that follows the room: the same nothing, forty decibels apart,
        // has to look the same. A fixed floor gets one of these two wrong by construction.
        for (level in listOf(0.00002f, 0.0005f, 0.02f)) {
            val graph = Spectrograph(sampleRate = rate)
            val columns = graph.add(room(2f, level))
            val brightest = columns.drop(5).maxOf { column -> column.max() }
            assertTrue(brightest < 0.45f, "room tone at $level was drawn at $brightest")
        }
    }

    @Test
    fun `a quiet bird after a loud one is still visible`() {
        val graph = Spectrograph(sampleRate = rate)
        graph.add(tone(3_000f, 1f))                        // a lorry
        graph.add(room(3f))                                // three seconds for the ceiling to fall
        val column = graph.add(tone(3_000f, 0.5f, amplitude = 0.02f)).last()
        assertTrue(
            column[loudestRow(column)] > 0.6f,
            "a bird 34 dB down was drawn at ${column[loudestRow(column)]}",
        )
    }

    @Test
    fun `a steady singer does not fade out`() {
        // A bush-cricket stridulates for minutes. Any noise model that subtracts what has been
        // there a while erases it — which would be this app deleting one of the groups it was
        // built for. Twenty seconds of unbroken 12 kHz, as bright at the end as at the start.
        val graph = Spectrograph(sampleRate = rate)
        val columns = graph.add(tone(12_000f, 20f, amplitude = 0.05f))
        val first = columns[10]
        val last = columns.last()
        assertEquals(loudestRow(first), loudestRow(last), "the cricket moved")
        assertTrue(
            last[loudestRow(last)] > 0.6f,
            "after twenty seconds the cricket had faded to ${last[loudestRow(last)]}",
        )
    }

    @Test
    fun `columns arrive at the rate the hop promises`() {
        val graph = Spectrograph(sampleRate = rate)
        // One second of audio is one second of picture, however the microphone chunks it.
        var whole = 0
        graph.reset()
        whole += graph.add(tone(1_000f, 1f)).size

        graph.reset()
        var chunked = 0
        val chunks = tone(1_000f, 1f)
        var at = 0
        while (at < chunks.size) {
            val take = minOf(777, chunks.size - at)
            chunked += graph.add(chunks.copyOfRange(at, at + take)).size
            at += take
        }
        assertEquals(whole, chunked, "chunking changed how much picture a second makes")
        val expected = rate / Spectrogram.HOP
        assertTrue(whole in (expected - 1)..expected, "$whole columns for one second, not ~$expected")
    }

    @Test
    fun `the frequency axis is labelled with frequencies that are actually there`() {
        assertEquals(Spectrogram.LOW_HZ, Spectrogram.hzAt(0f), 0.5f)
        assertEquals(Spectrogram.HIGH_HZ, Spectrogram.hzAt(1f), 5f)
        // Mel, not linear: the middle of the picture is well below the middle of the range.
        assertTrue(Spectrogram.hzAt(0.5f) < 4_000f, "midpoint at ${Spectrogram.hzAt(0.5f)} Hz")
    }

    @Test
    fun `every row has at least one bin, at every size worth drawing`() {
        for (fft in listOf(512, 1024, 2048, 4096)) {
            val edges = Spectrogram.edges(Spectrogram.BINS, fft, rate)
            for (i in 0 until Spectrogram.BINS) {
                assertTrue(edges[i + 1] > edges[i] || edges[i] == fft / 2, "empty row $i at fft $fft")
            }
            assertTrue(edges.last() <= fft / 2, "the top edge ran past Nyquist at fft $fft")
        }
    }

    @Test
    fun `the colour scale runs dark to bright and stays in range`() {
        assertEquals(Spectrogram.SILENCE, Spectrogram.colour(0f))
        assertEquals(Spectrogram.colour(0f), Spectrogram.colour(-1f), "out of range should clamp")
        assertEquals(Spectrogram.colour(1f), Spectrogram.colour(2f), "out of range should clamp")

        fun luminance(argb: Int): Float {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        var previous = -1f
        for (step in 0..20) {
            val colour = Spectrogram.colour(step / 20f)
            assertEquals(0xFF, (colour ushr 24) and 0xFF, "colours must be opaque")
            val here = luminance(colour)
            assertTrue(here > previous, "viridis got darker at ${step / 20f}")
            previous = here
        }
    }
}
