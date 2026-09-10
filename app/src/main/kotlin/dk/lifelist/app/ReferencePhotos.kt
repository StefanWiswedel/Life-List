package dk.lifelist.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The photographs the identification screen compares against, and who took them.
 *
 * 2,294 CC-licensed images by named people, fetched into assets by CI. Credit is not a
 * nicety here: CC-BY and CC-BY-SA require it, and BUILD.md §8 called the obligation real
 * before there was a screen to put it on.
 */
class ReferencePhotos(private val context: Context) {

    @Serializable
    data class Credit(val credit: String, val licence: String)

    private val json = Json { ignoreUnknownKeys = true }

    private val credits: Map<String, Credit> by lazy {
        runCatching {
            val text = context.assets.open("reference/credits.json")
                .use { it.readBytes().decodeToString() }
            json.decodeFromString<Map<String, Credit>>(text)
        }.getOrDefault(emptyMap())
    }

    private val cache = mutableMapOf<Int, Bitmap?>()
    private val thumbnails = mutableMapOf<Int, Bitmap?>()

    fun photo(taxonId: Int): Bitmap? = cache.getOrPut(taxonId) {
        runCatching {
            context.assets.open("reference/$taxonId.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    /**
     * A small square for a list row, decoded small rather than scaled down afterwards.
     *
     * A family of eleven bush-crickets is eleven 500 px photographs, and decoding them at full
     * size to draw them at 44 dp is about 11 MB of heap for 200 KB of pixels anybody can see.
     * `inSampleSize` decodes at a power-of-two fraction, so the bitmap that reaches memory is
     * already the size of the hole it goes in.
     *
     * Cached apart from [photo]: the same taxon can want both — a thumbnail in the roster and
     * the full photograph on its page — and a cache holding one at the other's size would make
     * whichever came second look wrong.
     */
    fun thumbnail(taxonId: Int, pixels: Int = THUMBNAIL): Bitmap? = thumbnails.getOrPut(taxonId) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("reference/$taxonId.jpg").use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val smallest = minOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (smallest / (sample * 2) >= pixels) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open("reference/$taxonId.jpg").use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    fun credit(taxonId: Int): Credit? = credits[taxonId.toString()]

    companion object {
        /** Roughly twice a 44 dp row at 3x, which is what the sampling rounds down towards. */
        const val THUMBNAIL = 160
    }

    /** True when this build shipped reference photos at all. */
    val available: Boolean by lazy { credits.isNotEmpty() }
}
