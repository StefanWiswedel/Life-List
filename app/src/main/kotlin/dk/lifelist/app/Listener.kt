package dk.lifelist.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dk.lifelist.core.Audio
import dk.lifelist.core.CertaintyTable
import dk.lifelist.core.LifeList
import dk.lifelist.core.Taxonomy
import java.io.File
import java.nio.FloatBuffer

/**
 * On-device listening.
 *
 * The division of labour is the same one [Identifier] keeps, and for the same reason: the
 * graph owns everything that could silently disagree with training. BirdNET V3.0 takes a raw
 * mono waveform — `[batch, 160000]` float32, five seconds at 32 kHz — and does its own
 * spectrogram inside the graph (the published metadata reports `has_stft_op: false`, the STFT
 * having been rewritten as a Conv1d, which is also why every op has a wide kernel). So this
 * class feeds samples and nothing else. There is no window function, no mel filterbank and no
 * normalisation here to get wrong.
 *
 * What it emits is BirdNET's own semantics, undistorted: independent per-class sigmoid
 * confidences. Turning those into an answer belongs to `dk.lifelist.core.Audio` (spec §4A),
 * which is shared with the Python reference and pinned by `golden_audio.json`.
 */
class Listener(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    val taxonomy: Taxonomy,
    /** BirdNET output position → GBIF taxon key. Index order is the contract. */
    private val classes: Map<Int, Int>,
) : AutoCloseable {

    companion object {
        const val MODEL_ASSET = "birdnet.onnx"
        const val TAXONOMY_ASSET = "audio_taxonomy.json"
        const val CLASSES_ASSET = "birdnet_classes.json"

        /** Five seconds at 32 kHz. Both numbers are the model's, not ours (VERIFICATION §61). */
        const val SAMPLE_RATE = 32_000
        const val WINDOW_SAMPLES = 160_000

        /**
         * How far each window advances. A bird that sings across a window boundary is otherwise
         * half a song in two windows and recognisable in neither, so windows overlap by half.
         */
        const val HOP_SAMPLES = WINDOW_SAMPLES / 2

        sealed interface Outcome {
            data class Ready(val listener: Listener) : Outcome
            data object NotBundled : Outcome
            data class Failed(val reason: String) : Outcome
        }

        fun openOrReport(context: Context): Outcome {
            val bundled = runCatching {
                context.assets.openFd(MODEL_ASSET).use { it.length }
            }.getOrNull()
            if (bundled == null || bundled <= 0L) return Outcome.NotBundled

            return runCatching { Outcome.Ready(open(context)) }
                .getOrElse { error ->
                    Outcome.Failed("${error::class.simpleName}: ${error.message ?: "no detail"}")
                }
        }

        /**
         * Materialise the model as a file so ORT can map it, exactly as [Identifier] does —
         * 149 MB through `readBytes()` would die on a device with a 256 MB heap while working
         * everywhere with real RAM.
         */
        fun modelFile(context: Context): File {
            val destination = File(context.filesDir, MODEL_ASSET)
            val expected = context.assets.openFd(MODEL_ASSET).use { it.length }
            if (destination.exists() && destination.length() == expected) return destination

            val temporary = File(context.filesDir, "$MODEL_ASSET.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            check(temporary.renameTo(destination)) { "could not move the audio model into place" }
            return destination
        }

        fun open(context: Context): Listener {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return Listener(
                environment.createSession(modelFile(context).absolutePath, options),
                environment,
                TaxonomyAssets.loadTaxonomy(context, TAXONOMY_ASSET),
                AudioAssets.loadClasses(context, CLASSES_ASSET),
            )
        }
    }

    /**
     * One window of samples → BirdNET's raw sigmoid scores, keyed by GBIF taxon.
     *
     * Classes with no GBIF crossing are dropped: they cannot be represented in a life list, and
     * the crossing is 801 of 801 as shipped (VERIFICATION §62). Where two classes name one taxon
     * — a split BirdNET models and GBIF does not — the **maximum** wins rather than the sum.
     * These are independent confidences, not a partition of probability mass; adding them could
     * exceed 1 and would overstate certainty exactly where the taxonomy is least sure.
     */
    fun score(samples: FloatArray): Map<Int, Float> {
        require(samples.size == WINDOW_SAMPLES) {
            "expected $WINDOW_SAMPLES samples, got ${samples.size}"
        }
        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(samples),
            longArrayOf(1, WINDOW_SAMPLES.toLong()),
        )
        val raw = input.use {
            session.run(mapOf(session.inputNames.first() to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result.get(0).value as Array<FloatArray>)[0]
            }
        }

        val out = HashMap<Int, Float>(raw.size)
        for (index in raw.indices) {
            val taxonId = classes[index] ?: continue
            val score = raw[index]
            val held = out[taxonId]
            if (held == null || score > held) out[taxonId] = score
        }
        return out
    }

    /**
     * Detect, then resolve each detection to the deepest rank it can defend (spec §4A).
     *
     * **Each detection is resolved at its own group's threshold**, from the table §59 fitted —
     * so a bird is answered at a bird's threshold and a frog at whatever amphibians cost, in
     * the same window, from the same recording. Answering a whole window at one number would
     * undo the argument that per-group thresholds exist to make: the same 95% is 0.70 for an
     * insect and 0.82 for a bird, and a soundscape is exactly where both turn up at once.
     */
    fun listen(
        samples: FloatArray,
        certainty: CertaintyTable,
        target: Float,
        windowStartS: Float = 0f,
        detectionThreshold: Float = Audio.DEFAULT_DETECTION_THRESHOLD,
        geo: Map<Int, Float>? = null,
    ): List<Audio.AudioIdentification> {
        val scores = score(samples)
        return Audio.detect(scores, windowStartS, detectionThreshold).map { detection ->
            val group = LifeList.groupOf(taxonomy, detection.taxonId)
            Audio.identify(
                taxonomy,
                scores,
                detection,
                threshold = certainty.certaintyFor(target, group).threshold,
                geo = geo,
            )
        }
    }

    override fun close() = session.close()
}
