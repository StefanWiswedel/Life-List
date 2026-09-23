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

    private val checklistCredits: Map<String, Credit> by lazy { load("$CHECKLIST/credits.json") }

    private val credits: Map<String, Credit> by lazy { load("$REFERENCE/credits.json") }

    private fun load(path: String): Map<String, Credit> = runCatching {
        val text = context.assets.open(path).use { it.readBytes().decodeToString() }
        json.decodeFromString<Map<String, Credit>>(text)
    }.getOrDefault(emptyMap())

    private val cache = mutableMapOf<Int, Bitmap?>()
    private val thumbnails = mutableMapOf<Pair<Int, Int>, Bitmap?>()

    /**
     * Two directories, one lookup.
     *
     * `reference/` holds 500 px photographs of the 3,430 species the model can identify — the
     * ones drawn full-width on the result screen, where §37 measured that 240 px is "a rumour
     * of a moth". `checklist/` holds 240 px of the other 18,957, because 500 px for all of
     * them is 762 MB and an APK nobody sideloads.
     *
     * The order matters: a species in both takes the bigger one.
     */
    fun photo(taxonId: Int): Bitmap? = cache.getOrPut(taxonId) {
        open("$REFERENCE/$taxonId.jpg") ?: open("$CHECKLIST/$taxonId.jpg")
    }

    private fun open(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

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
     *
     * **Keyed by the size as well as the taxon**, for that same reason one level down. The first
     * version keyed on taxon alone, so a bird wanted at 44dp in a roster and at 116dp on a
     * recent card got whichever size asked first, and which that was depended on which screen
     * you happened to open.
     */
    fun thumbnail(taxonId: Int, pixels: Int = THUMBNAIL): Bitmap? =
        thumbnails.getOrPut(taxonId to pixels) {
        runCatching {
            val directory = if (exists("$REFERENCE/$taxonId.jpg")) REFERENCE else CHECKLIST
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("$directory/$taxonId.jpg").use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val smallest = minOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (smallest / (sample * 2) >= pixels) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open("$directory/$taxonId.jpg").use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    fun credit(taxonId: Int): Credit? =
        credits[taxonId.toString()] ?: checklistCredits[taxonId.toString()]

    private fun exists(path: String): Boolean =
        runCatching { context.assets.open(path).use { true } }.getOrDefault(false)

    companion object {
        /** Roughly twice a 44 dp row at 3x, which is what the sampling rounds down towards. */
        const val THUMBNAIL = 160

        /** 500 px, for the species the model can name and the screens that draw them big. */
        const val REFERENCE = "reference"

        /** 240 px, for the other 18,957 — see [photo]. */
        const val CHECKLIST = "checklist"
    }

    /** True when this build shipped reference photos at all. */
    val available: Boolean by lazy { credits.isNotEmpty() }
}
