package dk.lifelist.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dk.lifelist.core.WindowBuffer
import dk.lifelist.core.pcm16ToFloat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone, and nothing else.
 *
 * Deliberately thin, because none of it can be executed anywhere but a phone. Everything that
 * *can* be tested has been moved out: the windowing arithmetic and the PCM conversion live in
 * `dk.lifelist.core.WindowBuffer`, with tests, and the identification is spec §4A in `core`
 * against a golden fixture. What is left here is the part that would need a device either way,
 * kept small enough to read in one go.
 *
 * That split is §53's lesson taken seriously: a screen whose content came from a system service
 * this environment cannot run was reasoned about carefully and shipped broken. The way to be
 * wrong less often is to leave less unrunnable code, not to reason harder about it.
 */
class Recorder(
    private val sampleRate: Int = Listener.SAMPLE_RATE,
    private val windowSamples: Int = Listener.WINDOW_SAMPLES,
    private val hopSamples: Int = Listener.HOP_SAMPLES,
) {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun stop() {
        running.set(false)
    }

    /**
     * Record until [stop], handing each complete window to [onWindow] on the calling thread.
     *
     * Blocking, and meant to be called on a background thread. `onWindow` runs inference, which
     * at ~100 ms a window against a 2.5 s hop leaves the microphone plenty of room — but if it
     * ever does fall behind, the samples queue in the buffer rather than being dropped, so the
     * timestamps stay true even when the analysis lags.
     */
    @SuppressLint("MissingPermission") // the caller holds RECORD_AUDIO; see ListenScreen
    fun record(onWindow: (WindowBuffer.Window) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "this device cannot record 16-bit mono at $sampleRate Hz" }

        // Four times the minimum: the minimum is what the hardware needs to not glitch, not what
        // leaves room for a slow consumer, and an overrun is silently lost audio.
        val bufferBytes = minimum * 4
        val record = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { supportsUnprocessed() }
                ?: MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "could not open the microphone" }

        val windows = WindowBuffer(windowSamples, hopSamples, sampleRate)
        val scratch = ShortArray(bufferBytes / 2)
        running.set(true)
        try {
            record.startRecording()
            while (running.get()) {
                val read = record.read(scratch, 0, scratch.size)
                if (read <= 0) continue
                // Only what was actually read: the rest of `scratch` is the previous read's
                // audio, and feeding that to the model is a stutter it will try to identify.
                windows.add(pcm16ToFloat(scratch, read)).forEach(onWindow)
            }
            windows.flush()?.let(onWindow)
        } finally {
            running.set(false)
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * `UNPROCESSED` where the device offers it.
     *
     * The default `MIC` source runs whatever noise suppression and automatic gain the phone
     * ships for speech, which is tuned to keep a voice and discard everything else — exactly
     * the wrong instinct for a bush-cricket. Not every device has it, hence the fallback.
     */
    private fun supportsUnprocessed(): Boolean = runCatching {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
    }.getOrDefault(false)
}
