package dk.lifelist.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A picture of the sound, while it is still being heard.
 *
 * This is not how the app identifies anything. BirdNET does its own spectrogram inside the ONNX
 * graph (`Listener`), and reimplementing that on the client is exactly the mistake §22 was
 * written about. What is here is for the person holding the phone: a scrolling display that
 * shows a song arrived, shows the shape of it, and shows a passing lorry for what it is. With
 * no picture, a screen that has found nothing for forty seconds is indistinguishable from a
 * screen that has stopped working.
 *
 * So the numbers below are chosen to be *legible*, not to match the model. Frequency is
 * mel-spaced because that is where birds put their detail; loudness is in decibels against a
 * ceiling that follows the recording, because a fixed floor either blacks out a distant bird or
 * lights up the room tone, and which of those happens is a fact about the afternoon rather than
 * about the bird.
 */
object Spectrogram {

    /** 64 ms at 32 kHz. Long enough to separate two notes a tone apart, short enough to see a trill. */
    const val FFT_SIZE = 2048

    /** 50 ms at 32 kHz — twenty columns a second, and the windows overlap. */
    const val HOP = 1600

    /** Rows in the picture. */
    const val BINS = 64

    const val LOW_HZ = 100f
    const val HIGH_HZ = 15_000f

    /**
     * How far above the room's own noise is drawn as black.
     *
     * The display has two moving parts, not one. The **ceiling** follows the loudest thing
     * lately and decides what full brightness means; the **floor** follows the room and decides
     * what black means. A single fixed floor cannot do both jobs: set it low and a kitchen
     * fills the screen with its own hiss, set it high and a distant warbler is never drawn.
     *
     * The floor tracks the *median* row of each column rather than the quietest, because the
     * median is the room — a bird occupies a few rows out of sixty-four and cannot move it.
     * That matters more here than it would in a bird-only display: a bush-cricket stridulates
     * for minutes without a break, and any scheme that subtracts "whatever has been there a
     * while" would quietly erase one of the species this app exists to hear.
     */
    const val MARGIN_DB = 6f

    /** The narrowest the scale may get, so a quiet room is not amplified into a rainbow. */
    const val MIN_SPAN_DB = 25f

    /** How fast the ceiling may fall, in dB per column: 10 dB a second. */
    const val DECAY_DB = 0.5f

    /** How fast the floor follows the room. About a second. */
    const val NOISE_SMOOTHING = 0.05f

    fun hann(size: Int): FloatArray =
        FloatArray(size) { 0.5f - 0.5f * cos(2.0 * PI * it / size).toFloat() }

    /** Hertz ↔ mel, O'Shaughnessy's constants — the ones librosa's `htk=True` uses. */
    fun mel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

    fun hz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    /** The frequency drawn at [fraction] of the way up the picture. For axis labels. */
    fun hzAt(fraction: Float): Float =
        hz(mel(LOW_HZ) + (mel(HIGH_HZ) - mel(LOW_HZ)) * fraction.coerceIn(0f, 1f))

    /**
     * Which FFT bins belong to each row: `bins + 1` mel-spaced edges, as bin indices.
     *
     * Every row gets at least one bin. Down at 100 Hz the mel scale asks for a row narrower
     * than the FFT's own resolution, and a row with no bins in it is a black stripe across the
     * bottom of every recording ever made.
     */
    fun edges(bins: Int, fftSize: Int, sampleRate: Int): IntArray {
        require(bins > 0) { "bins must be positive, got $bins" }
        val perBin = sampleRate.toFloat() / fftSize
        val lowMel = mel(LOW_HZ)
        val span = mel(HIGH_HZ.coerceAtMost(sampleRate / 2f)) - lowMel
        val out = IntArray(bins + 1)
        var previous = -1
        for (i in 0..bins) {
            val frequency = hz(lowMel + span * i / bins)
            val bin = (frequency / perBin).toInt().coerceIn(0, fftSize / 2)
            out[i] = if (bin > previous) bin else previous + 1
            previous = out[i]
        }
        // The rounding above can walk the top edge past Nyquist on a small FFT; pull it back
        // and let the highest rows share bins rather than read off the end of the array.
        for (i in bins downTo 0) if (out[i] > fftSize / 2) out[i] = fftSize / 2
        return out
    }

    /** Viridis: dark where it is quiet, and legible to the colour-blind. */
    private val ANCHORS = intArrayOf(
        0x440154, 0x482878, 0x3E4A89, 0x31688E, 0x26828E, 0x1F9E89,
        0x35B779, 0x6DCD59, 0xB4DE2C, 0xDCE319, 0xFDE725,
    )

    /** The colour of an intensity in 0..1, as opaque ARGB. */
    fun colour(value: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        val position = clamped * (ANCHORS.size - 1)
        val low = position.toInt().coerceAtMost(ANCHORS.size - 2)
        val t = position - low
        val a = ANCHORS[low]
        val b = ANCHORS[low + 1]
        fun mix(shift: Int): Int {
            val from = (a shr shift) and 0xFF
            val to = (b shr shift) and 0xFF
            return (from + (to - from) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** The colour of silence, for filling an empty display. */
    val SILENCE: Int = colour(0f)
}

/**
 * Sample chunks in, columns of the picture out.
 *
 * Stateful — it holds the tail of the last chunk so a song is not cut at the seam between two
 * microphone reads — but pure, and therefore testable on the JVM. The phone-shaped parts
 * (`Recorder`, the canvas) do no arithmetic at all.
 */
class Spectrograph(
    private val sampleRate: Int = 32_000,
    private val fftSize: Int = Spectrogram.FFT_SIZE,
    private val hop: Int = Spectrogram.HOP,
    private val bins: Int = Spectrogram.BINS,
) {
    init {
        require(fftSize > 1 && fftSize and (fftSize - 1) == 0) {
            "fftSize must be a power of two, got $fftSize"
        }
        require(hop in 1..fftSize) { "hop must be in 1..$fftSize, got $hop" }
    }

    private val window = Spectrogram.hann(fftSize)
    private val edges = Spectrogram.edges(bins, fftSize, sampleRate)
    private val cosines = FloatArray(fftSize / 2) { cos(-2.0 * PI * it / fftSize).toFloat() }
    private val sines = FloatArray(fftSize / 2) { sin(-2.0 * PI * it / fftSize).toFloat() }

    /** A full-scale sine through this window. 0 dB is as loud as the microphone can be. */
    private val reference = fftSize / 4f

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private var pending = FloatArray(0)

    /** The loudest thing heard lately, in dB. What the top of the colour scale means. */
    var ceilingDb: Float = -200f
        private set

    /** The room, in dB. What the bottom of the colour scale means. */
    var noiseDb: Float = -200f
        private set

    private var seeded = false

    fun reset() {
        pending = FloatArray(0)
        ceilingDb = -200f
        noiseDb = -200f
        seeded = false
    }

    /**
     * Add samples, take whatever columns they complete.
     *
     * Each column is [bins] intensities in 0..1, lowest frequency first.
     */
    fun add(samples: FloatArray, count: Int = samples.size): List<FloatArray> {
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        pending = pending + (if (count == samples.size) samples else samples.copyOf(count))

        val out = mutableListOf<FloatArray>()
        var at = 0
        while (pending.size - at >= fftSize) {
            out += column(at)
            at += hop
        }
        if (at > 0) pending = pending.copyOfRange(at, pending.size)
        return out
    }

    private fun column(offset: Int): FloatArray {
        for (i in 0 until fftSize) {
            re[i] = pending[offset + i] * window[i]
            im[i] = 0f
        }
        transform()

        val decibels = FloatArray(bins)
        var loudest = -200f
        for (b in 0 until bins) {
            var peak = 0f
            val from = edges[b]
            val to = maxOf(edges[b + 1], from + 1)
            for (k in from until minOf(to, fftSize / 2)) {
                val power = re[k] * re[k] + im[k] * im[k]
                if (power > peak) peak = power
            }
            val db = 20f * log10(maxOf(sqrt(peak) / reference, 1e-9f))
            decibels[b] = db
            if (db > loudest) loudest = db
        }

        // The ceiling jumps to a loud sound at once and slides down afterwards, so the display
        // does not flare on every syllable. The floor is seeded from the first column rather
        // than faded in from nothing, which would spend the first second of every session
        // showing a screen full of noise going dark.
        val median = decibels.copyOf().also { it.sort() }[bins / 2]
        noiseDb = if (seeded) {
            noiseDb + (median - noiseDb) * Spectrogram.NOISE_SMOOTHING
        } else {
            seeded = true
            median
        }
        ceilingDb = maxOf(loudest, ceilingDb - Spectrogram.DECAY_DB)

        val floor = noiseDb + Spectrogram.MARGIN_DB
        val span = maxOf(ceilingDb, floor + Spectrogram.MIN_SPAN_DB) - floor
        return FloatArray(bins) { ((decibels[it] - floor) / span).coerceIn(0f, 1f) }
    }

    /** In-place radix-2 FFT over [re]/[im]. Tabulated twiddles: eleven stages of a float
     *  recurrence drifts, and the drift lands on exactly the quiet bins the picture is about. */
    private fun transform() {
        val n = fftSize
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var swap = re[i]; re[i] = re[j]; re[j] = swap
                swap = im[i]; im[i] = im[j]; im[j] = swap
            }
        }

        var length = 2
        while (length <= n) {
            val half = length / 2
            val step = n / length
            var base = 0
            while (base < n) {
                for (k in 0 until half) {
                    val wr = cosines[k * step]
                    val wi = sines[k * step]
                    val upper = base + k + half
                    val vr = re[upper] * wr - im[upper] * wi
                    val vi = re[upper] * wi + im[upper] * wr
                    val ur = re[base + k]
                    val ui = im[base + k]
                    re[base + k] = ur + vr
                    im[base + k] = ui + vi
                    re[upper] = ur - vr
                    im[upper] = ui - vi
                }
                base += length
            }
            length = length shl 1
        }
    }
}
