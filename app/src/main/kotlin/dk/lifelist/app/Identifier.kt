package dk.lifelist.app

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import dk.lifelist.core.Rollup
import dk.lifelist.core.RollupResult
import dk.lifelist.core.Taxonomy
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * On-device identification.
 *
 * The graph does resize, normalisation, the backbone, L2 and the head. This class does
 * three things and nothing else: crop to a square, feed bytes, apply temperature.
 *
 * That division is deliberate. Every step this file *could* have done — a bilinear resize,
 * a forgotten normalisation — is a step that would have silently disagreed with training.
 * Bilinear instead of antialiased bicubic alone costs 8% of predictions (VERIFICATION.md
 * §22), and nothing on the screen would say so.
 */
class Identifier(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    val taxonomy: Taxonomy,
    private val temperature: Float,
) : AutoCloseable {

    companion object {
        const val MODEL_ASSET = "lifelist.onnx"

        /**
         * The outcome of trying to open the model, including *why* it failed.
         *
         * The first version returned a bare null and the screen said "no model in this
         * build". The model was in the build; it failed to load, and the message sent
         * everyone looking at CI. A missing asset and a broken one must not read the same.
         */
        sealed interface Outcome {
            data class Ready(val identifier: Identifier) : Outcome
            data object NotBundled : Outcome
            data class Failed(val reason: String) : Outcome
        }

        fun openOrReport(context: Context, taxonomy: Taxonomy, temperature: Float): Outcome {
            val bundled = runCatching {
                context.assets.openFd(MODEL_ASSET).use { it.length }
            }.getOrNull()
            if (bundled == null || bundled <= 0L) return Outcome.NotBundled

            return runCatching { Outcome.Ready(open(context, taxonomy, temperature)) }
                .getOrElse { error ->
                    Outcome.Failed("${error::class.simpleName}: ${error.message ?: "no detail"}")
                }
        }

        /**
         * Materialise the model as a file, then let ONNX Runtime map it.
         *
         * Not `assets.open().readBytes()`. That pulls 335 MB into the Java heap, and
         * Android's default heap is around 256 MB — it dies with OutOfMemoryError on a
         * device while working fine anywhere with real RAM. Copying once to internal
         * storage costs disk we have already spent, and lets ORT map the file instead of
         * holding it twice.
         */
        fun modelFile(context: Context): File {
            val destination = File(context.filesDir, MODEL_ASSET)
            val expected = context.assets.openFd(MODEL_ASSET).use { it.length }
            // Size is the version check: a new APK ships a different model, and a partial
            // copy from a kill mid-write does not match either.
            if (destination.exists() && destination.length() == expected) return destination

            val temporary = File(context.filesDir, "$MODEL_ASSET.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            check(temporary.renameTo(destination)) { "could not move the model into place" }
            return destination
        }

        fun open(context: Context, taxonomy: Taxonomy, temperature: Float): Identifier {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // §4.1: CPU execution provider, threads set deliberately rather than left
                // to a default that assumes a server.
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return Identifier(
                environment.createSession(modelFile(context).absolutePath, options),
                environment, taxonomy, temperature,
            )
        }
    }

    /**
     * Centre-crop to a square and return tightly packed RGB bytes.
     *
     * Exact, not approximate: cropping to a square of side `min(w, h)` and letting the graph
     * scale it to 224 gives the same pixels as scaling the short side to 224 and then
     * cropping 224. Integer arithmetic, so there is nothing here to drift.
     */
    fun squarePixels(bitmap: Bitmap): Triple<ByteBuffer, Int, Int> {
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2

        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, left, top, side, side)

        val buffer = ByteBuffer.allocateDirect(side * side * 3)
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())
            buffer.put(((pixel shr 8) and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }
        buffer.rewind()
        return Triple(buffer, side, side)
    }

    /** Raw logits for one photo. */
    fun logits(bitmap: Bitmap): FloatArray {
        val (buffer, height, width) = squarePixels(bitmap)
        val shape = longArrayOf(1, height.toLong(), width.toLong(), 3)
        // UINT8 rather than a float tensor: the graph casts, and shipping 3 bytes per pixel
        // instead of 12 keeps a 5-photo batch at 4 MB rather than 16 on a phone.
        OnnxTensor.createTensor(
            environment, buffer, shape, OnnxJavaType.UINT8
        ).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<FloatArray>
                    return out[0]
                }
            }
    }

    /**
     * Softmax with the fitted temperature, applied exactly once (spec §2).
     *
     * Outside the graph on purpose: the threshold is adjustable at display time (§4.4), and
     * a temperature baked into the export would freeze at build time something the user is
     * meant to be able to move.
     */
    fun probabilities(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (value in logits) if (value > max) max = value
        var total = 0.0
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp(((logits[i] - max) / temperature).toDouble())
            out[i] = e.toFloat()
            total += e
        }
        for (i in out.indices) out[i] = (out[i] / total).toFloat()
        return out
    }

    fun identify(bitmap: Bitmap, threshold: Float): RollupResult =
        Rollup.rollup(taxonomy, probabilities(logits(bitmap)), threshold)

    override fun close() {
        session.close()
    }
}
