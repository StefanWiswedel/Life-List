package dk.lifelist.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A bigger copy of a photograph the app already has, when there is signal.
 *
 * The app is offline-first and stays offline-first: both models are bundled, both taxonomies
 * are bundled, identification never touches a network and never will. This is the one
 * exception and it is deliberately the smallest one that solves the problem.
 *
 * The problem is arithmetic. 18,957 checklist species ship a 240 px photograph, which is more
 * than a 64 dp row needs and visibly soft drawn full-width on a species page — the exact
 * complaint §37 was written about. 500 px for all of them is 762 MB of APK on top of 600.
 * So the bundled photograph is what you always get, and a 1024 px copy replaces it once, is
 * kept, and is never waited for.
 *
 * **It cannot fail visibly.** There is no spinner, no error and no retry button: the screen is
 * already showing a photograph. If the fetch does not land, nothing happens, which is the
 * correct amount of fuss for an upgrade nobody asked for.
 */
class LargePhotos(private val context: Context) {

    private val directory = File(context.cacheDir, "large").apply { mkdirs() }

    /** The kept copy, if a previous visit fetched one. */
    fun cached(taxonId: Int): Bitmap? {
        val file = File(directory, "$taxonId.jpg")
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Fetch and keep, returning the bitmap. Blocking — call it off the main thread.
     *
     * Written to a temporary file and renamed, so a fetch cut off halfway leaves nothing
     * rather than a truncated JPEG that decodes to grey and is then cached forever.
     */
    fun fetch(taxonId: Int, photoId: Long, extension: String): Bitmap? {
        cached(taxonId)?.let { return it }
        val destination = File(directory, "$taxonId.jpg")
        val temporary = File(directory, "$taxonId.jpg.tmp")
        return runCatching {
            val url = URL(urlFor(photoId, extension))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 0) { "empty download" }
            check(temporary.renameTo(destination)) { "could not keep the photograph" }
            BitmapFactory.decodeFile(destination.absolutePath)
        }.getOrElse {
            temporary.delete()
            null
        }
    }

    /** How much of the phone this has quietly taken. Shown on the settings sheet. */
    fun keptBytes(): Long =
        directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun forget() {
        directory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val USER_AGENT = "LifeList/0.13 (https://github.com/StefanWiswedel/Life-List)"

        /**
         * iNaturalist's open-data bucket, the same host the bundled photographs came from.
         *
         * `large` is 1024 px on the longest side and about 110 KB — four times the 240 px the
         * app ships and a tenth of what an original would be.
         */
        fun urlFor(photoId: Long, extension: String): String =
            "https://inaturalist-open-data.s3.amazonaws.com/photos/$photoId/large." +
                extension.trimStart('.').ifBlank { "jpeg" }
    }
}
