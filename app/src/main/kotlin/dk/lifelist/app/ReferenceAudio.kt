package dk.lifelist.app

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The recording of what this species is supposed to sound like, and who recorded it.
 *
 * The sibling of [ReferencePhotos], and the point is the same one §37 made about pictures: the
 * app puts its answer next to the evidence and lets you disagree. For a bird you heard rather
 * than saw, the evidence is a sound — your five seconds against ten seconds of a clean
 * recording, one after the other.
 *
 * Ten-second Opus clips from xeno-canto, CC-licensed, about 30 KB each. Credit is not a nicety:
 * every licence in the index except CC0 requires it, and BUILD.md §8 called the obligation real
 * before there was a screen to put it on.
 *
 * **Copied out to a file rather than played from the asset.** `MediaPlayer` will take a file
 * descriptor, but a clip in the app's own storage plays the same way your own recording does,
 * through one code path instead of two.
 */
class ReferenceAudio(private val context: Context) {

    @Serializable
    data class Credit(val credit: String, val licence: String, val url: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    private val credits: Map<String, Credit> by lazy {
        runCatching {
            val text = context.assets.open("$DIRECTORY/credits.json")
                .use { it.readBytes().decodeToString() }
            json.decodeFromString<Map<String, Credit>>(text)
        }.getOrDefault(emptyMap())
    }

    private val unpacked = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    /** The clip's path on disk, unpacking it the first time. Null when this build has none. */
    fun clip(taxonId: Int): String? {
        if (credits[taxonId.toString()] == null) return null
        val destination = File(unpacked, "$taxonId.opus")
        if (destination.exists() && destination.length() > 0) return destination.absolutePath
        return runCatching {
            val temporary = File(unpacked, "$taxonId.opus.tmp")
            context.assets.open("$DIRECTORY/$taxonId.opus").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(destination)) { "could not unpack the reference clip" }
            destination.absolutePath
        }.getOrNull()
    }

    fun credit(taxonId: Int): Credit? = credits[taxonId.toString()]

    /** True when this build shipped reference recordings at all. */
    val available: Boolean by lazy { credits.isNotEmpty() }

    companion object {
        const val DIRECTORY = "reference-audio"
    }
}
