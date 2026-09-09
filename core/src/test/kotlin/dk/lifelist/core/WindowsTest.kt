package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic between a microphone and a model that wants exactly 160,000 samples.
 *
 * Small, dull, and the place a whole session's timestamps go wrong by one hop.
 */
class WindowsTest {

    private fun ramp(n: Int, from: Int = 0) = FloatArray(n) { (from + it).toFloat() }

    @Test
    fun `nothing comes back until a whole window has arrived`() {
        val buffer = WindowBuffer(windowSamples = 10, hopSamples = 5, sampleRate = 10)

        assertTrue(buffer.add(ramp(9)).isEmpty())
        assertEquals(9, buffer.buffered)
        assertEquals(1, buffer.add(ramp(1, from = 9)).size)
    }

    @Test
    fun `windows overlap by the hop, so a song across a boundary survives`() {
        val buffer = WindowBuffer(windowSamples = 10, hopSamples = 5, sampleRate = 10)

        val windows = buffer.add(ramp(20))

        assertEquals(listOf(0f, 0.5f, 1.0f), windows.map { it.startS })
        assertEquals(0f, windows[0].samples.first())
        assertEquals(5f, windows[1].samples.first())
        assertEquals(10f, windows[2].samples.first())
    }

    @Test
    fun `the microphone's buffer size does not change the windows`() {
        // The one property that matters: the same audio must window identically whether it
        // arrives in one lump or in the ragged chunks a real device hands over.
        val whole = WindowBuffer(10, 5, 10).add(ramp(43))

        val streamed = WindowBuffer(10, 5, 10).let { buffer ->
            var at = 0
            val out = mutableListOf<WindowBuffer.Window>()
            for (size in listOf(3, 1, 17, 9, 13)) {
                out += buffer.add(ramp(size, from = at))
                at += size
            }
            out
        }

        assertEquals(whole, streamed)
    }

    @Test
    fun `timestamps advance by the hop, not by the window`() {
        val buffer = WindowBuffer(windowSamples = 160_000, hopSamples = 80_000, sampleRate = 32_000)

        val windows = buffer.add(FloatArray(480_000))

        assertEquals(listOf(0f, 2.5f, 5f, 7.5f, 10f), windows.map { it.startS })
    }

    @Test
    fun `the final fragment is padded rather than dropped`() {
        val buffer = WindowBuffer(10, 5, 10)
        buffer.add(ramp(17))

        val last = buffer.flush()!!

        assertEquals(10, last.samples.size)
        assertEquals(1.0f, last.startS)
        assertEquals(0f, last.samples.last(), "padded with silence")
    }

    @Test
    fun `a fragment shorter than the hop is mostly silence and is dropped`() {
        val buffer = WindowBuffer(10, 5, 10)
        buffer.add(ramp(4))

        assertNull(buffer.flush())
    }

    @Test
    fun `flushing twice does not hand back the same audio again`() {
        val buffer = WindowBuffer(10, 5, 10)
        buffer.add(ramp(17))

        buffer.flush()

        assertNull(buffer.flush())
    }

    // -- PCM conversion -----------------------------------------------------------

    @Test
    fun `pcm is scaled by 32768, matching what the model was fed in training`() {
        val converted = pcm16ToFloat(shortArrayOf(0, 32767, -32768, 16384))

        assertEquals(0f, converted[0])
        assertEquals(32767f / 32768f, converted[1])
        assertEquals(-1f, converted[2])
        assertEquals(0.5f, converted[3])
    }

    @Test
    fun `a partly filled buffer converts only what was actually read`() {
        // AudioRecord returns how many samples it wrote, and the rest of the array is stale
        // audio from the last read. Converting all of it would feed the model a stutter.
        val converted = pcm16ToFloat(shortArrayOf(1, 2, 3, 4, 5), count = 3)

        assertEquals(3, converted.size)
    }
}
