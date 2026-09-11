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


/**
 * The window that produced a detection, as a playable WAV.
 *
 * A record that says "Eurasian Magpie, heard at 175s" and cannot play the 175 seconds is asking
 * to be taken on trust — which is the opposite of what the rest of this app does. Five seconds
 * of 16-bit mono at 32 kHz is 320 KB, less than one of the photographs already kept beside a
 * record.
 *
 * Written by hand rather than through a platform encoder: it is a 44-byte header and the samples
 * back in the integers they arrived as, it is identical on every device, and it can be tested
 * here rather than on a phone.
 */
fun wavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
    require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
    val dataBytes = samples.size * 2
    val out = ByteArray(44 + dataBytes)
    var at = 0

    fun ascii(text: String) {
        for (character in text) out[at++] = character.code.toByte()
    }

    fun le32(value: Int) {
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value ushr 8) and 0xFF).toByte()
        out[at++] = ((value ushr 16) and 0xFF).toByte()
        out[at++] = ((value ushr 24) and 0xFF).toByte()
    }

    fun le16(value: Int) {
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value ushr 8) and 0xFF).toByte()
    }

    ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
    ascii("fmt "); le32(16); le16(1); le16(1)          // PCM, mono
    le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
    ascii("data"); le32(dataBytes)

    for (sample in samples) {
        // Clamped before scaling: a sample at exactly 1.0 would otherwise wrap to -32768 and
        // put a click in the loudest part of the recording — the part worth listening to.
        val clamped = sample.coerceIn(-1f, 1f)
        val value = (clamped * 32767f).toInt()
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * Does a window of audio overlap a stretch of it we have decided not to trust?
 *
 * Written for one specific way of being wrong, found in the field: playing a clip back through
 * the phone's speaker while the session is still listening. The app heard itself, identified
 * itself, and the confidence climbed with every replay — a feedback loop that looks exactly
 * like growing certainty and is the opposite of evidence.
 *
 * The fix is not to stop recording. Timestamps come from a continuous count of samples, so a
 * pause would shift every later detection, and the picture of the sound is worth keeping
 * anyway. What is dropped is the *identification* of any window that overlaps the playback —
 * `[fromS, toS]`, in the same seconds-since-start clock the windows use.
 *
 * Both ends are open when nothing is playing: a `toS` of infinity means "still playing", which
 * is the state the screen is in while a clip sounds.
 */
fun windowOverlaps(windowStartS: Float, windowSeconds: Float, fromS: Float, toS: Float): Boolean {
    require(windowSeconds >= 0f) { "windowSeconds must not be negative, got $windowSeconds" }
    if (toS <= fromS) return false
    return windowStartS < toS && windowStartS + windowSeconds > fromS
}
