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

    // -- the clip -----------------------------------------------------------------

    @Test
    fun `the wav header says what the samples actually are`() {
        val wav = wavBytes(FloatArray(160_000), 32_000)

        assertEquals(44 + 320_000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(1, wav[22].toInt(), "one channel")
        assertEquals(16, wav[34].toInt(), "16 bits per sample")
        // sample rate, little-endian at offset 24
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        assertEquals(32_000, rate)
    }

    @Test
    fun `a sample at full scale does not wrap into a click`() {
        // Scaling 1.0 by 32768 lands on -32768 in two's complement, which is a click in the
        // loudest part of the recording — the part worth listening to.
        val wav = wavBytes(floatArrayOf(1f, -1f), 32_000)

        val first = ((wav[45].toInt() and 0xFF) shl 8) or (wav[44].toInt() and 0xFF)
        assertEquals(32767, first.toShort().toInt())
        val second = ((wav[47].toInt() and 0xFF) shl 8) or (wav[46].toInt() and 0xFF)
        assertEquals(-32767, second.toShort().toInt())
    }

    @Test
    fun `a sample beyond full scale is clamped rather than wrapped`() {
        val wav = wavBytes(floatArrayOf(4f), 32_000)

        val value = (((wav[45].toInt() and 0xFF) shl 8) or (wav[44].toInt() and 0xFF)).toShort()
        assertEquals(32767, value.toInt())
    }

    @Test
    fun `a round trip through pcm and back survives`() {
        val original = shortArrayOf(0, 12_345, -12_345, 32_767)

        val wav = wavBytes(pcm16ToFloat(original), 32_000)

        for (i in original.indices) {
            val value = (((wav[45 + i * 2].toInt() and 0xFF) shl 8) or
                (wav[44 + i * 2].toInt() and 0xFF)).toShort()
            // Within one count: the two scalings are 32768 and 32767, deliberately.
            assertTrue(kotlin.math.abs(value - original[i]) <= 1, "${original[i]} became $value")
        }
    }
}
