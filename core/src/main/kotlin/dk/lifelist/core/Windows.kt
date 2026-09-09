package dk.lifelist.core

/**
 * Cutting a live microphone stream into the fixed windows the model wants.
 *
 * The microphone delivers whatever size buffer it feels like; BirdNET takes exactly 160,000
 * float samples and nothing else. Between those two facts sits a small amount of arithmetic
 * that is very easy to get subtly wrong — an off-by-one in the hop shifts every reported
 * timestamp, and a boundary that drops samples loses whatever was sung across it — so it lives
 * here, in pure Kotlin, with tests, rather than inside a class that needs a device to run.
 *
 * Windows **overlap**, by half a window by default. A bird singing across a boundary is
 * otherwise half a song in each of two windows and recognisable in neither.
 */
class WindowBuffer(
    private val windowSamples: Int,
    private val hopSamples: Int,
    private val sampleRate: Int,
) {
    init {
        require(windowSamples > 0) { "windowSamples must be positive, got $windowSamples" }
        require(hopSamples in 1..windowSamples) {
            "hopSamples must be in 1..$windowSamples, got $hopSamples"
        }
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
    }

    /** One window, and where it began in the recording. */
    data class Window(val samples: FloatArray, val startS: Float) {
        // FloatArray in a data class: generated equals/hashCode compare identity, which for a
        // window of audio is never what anybody means. Spelled out so a test can compare two.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Window && startS == other.startS && samples.contentEquals(other.samples))

        override fun hashCode(): Int = 31 * samples.contentHashCode() + startS.hashCode()
    }

    private var pending = FloatArray(0)
    private var consumed = 0L

    /**
     * Add samples and take whatever complete windows they make.
     *
     * Returns empty until a whole window has arrived, which is normal and not a failure: at a
     * five-second window the first answer is five seconds away no matter how the microphone
     * chunks its buffers.
     */
    fun add(samples: FloatArray): List<Window> {
        pending = pending + samples
        val out = mutableListOf<Window>()
        while (pending.size >= windowSamples) {
            out += Window(pending.copyOfRange(0, windowSamples), consumed.toFloat() / sampleRate)
            pending = pending.copyOfRange(hopSamples, pending.size)
            consumed += hopSamples
        }
        return out
    }

    /**
     * The last, short window — zero-padded — or null when there is nothing worth analysing.
     *
     * Called when the person stops recording. Padding a fragment is what BirdNET's own tooling
     * does, but a fragment shorter than the hop is mostly silence pretending to be evidence, so
     * it is dropped instead.
     */
    fun flush(): Window? {
        if (pending.size < hopSamples) return null
        val padded = pending.copyOf(windowSamples)
        val window = Window(padded, consumed.toFloat() / sampleRate)
        pending = FloatArray(0)
        return window
    }

    /** Samples held back waiting for a complete window. Exposed for the tests and the UI. */
    val buffered: Int get() = pending.size
}

/**
 * 16-bit PCM as the microphone gives it → the float range the model was trained on.
 *
 * Divided by 32768, matching `librosa.load`, which is what BirdNET's own tooling feeds the
 * graph. Dividing by 32767 instead — the arithmetically tempting choice, since that is the
 * actual positive maximum — would scale every sample by 1.00003 and put the app permanently a
 * hair away from the reference implementation for no reason anybody could hear.
 */
fun pcm16ToFloat(pcm: ShortArray, count: Int = pcm.size): FloatArray {
    require(count in 0..pcm.size) { "count $count outside 0..${pcm.size}" }
    val out = FloatArray(count)
    for (i in 0 until count) out[i] = pcm[i] / 32768f
    return out
}
