package dk.lifelist.app

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Playing one short clip at a time.
 *
 * One at a time on purpose: two recordings over each other is not a comparison, it is a mess,
 * and the whole point of putting the reference beside your own is that you can hear them one
 * after the other and decide for yourself.
 *
 * `MediaPlayer` rather than `AudioTrack` because these are files on disk, both the five seconds
 * from the phone and the reference recording bundled with the app, and MediaPlayer already
 * knows how to read a WAV and an MP3 and to tell us when it has finished.
 */
class ClipPlayer {

    private var player: MediaPlayer? = null

    /**
     * The path currently sounding, so a button can show that it is the one playing.
     *
     * Compose state rather than a plain field: the button label depends on it, and a plain
     * field changes without anything recomposing, so the clip stops and the button still says
     * "Stop".
     */
    var playing: String? by mutableStateOf(null)
        private set

    /** Play [path], or stop if it is already the one playing. Returns the new state. */
    fun toggle(path: String, onFinished: () -> Unit = {}): String? {
        if (playing == path) {
            stop()
            return null
        }
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    playing = null
                    onFinished()
                }
                prepare()
                start()
            }
        }.getOrNull()
        playing = if (player != null) path else null
        return playing
    }

    fun stop() {
        player?.let { existing ->
            runCatching { if (existing.isPlaying) existing.stop() }
            existing.release()
        }
        player = null
        playing = null
    }
}

/**
 * A player tied to the composition, released when the screen goes away.
 *
 * Forgetting this leaks a `MediaPlayer` per visit and, worse, leaves a clip sounding after the
 * screen it belonged to has gone.
 */
@Composable
fun rememberClipPlayer(): ClipPlayer {
    val player = remember { ClipPlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}
