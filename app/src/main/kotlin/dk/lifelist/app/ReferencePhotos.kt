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

    fun photo(taxonId: Int): Bitmap? = cache.getOrPut(taxonId) {
        runCatching {
            context.assets.open("reference/$taxonId.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    fun credit(taxonId: Int): Credit? = credits[taxonId.toString()]

    /** True when this build shipped reference photos at all. */
    val available: Boolean by lazy { credits.isNotEmpty() }
}
