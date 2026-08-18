package dk.lifelist.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A paragraph about what you just photographed, shipped in the APK.
 *
 * Bundled rather than fetched: this app is offline-first and gets used in a field with no
 * signal, and an "about this" panel that is blank exactly when you are standing in front of
 * the animal is worse than no panel. Roughly 2 MB of text for the whole taxonomy — next to a
 * 350 MB model, free.
 *
 * Keyed by taxon id, including the negative ids of indeterminate leaves, which borrow their
 * genus's article. No name matching happens here; that decision was made at build time where
 * it could be tested (`training/src/lifelist_train/wikipedia.py`).
 *
 * Coverage is partial and always will be. English Wikipedia has an article for most Danish
 * birds and mammals and for a minority of the fungi and micro-moths. A missing article shows
 * nothing rather than an apology.
 */
class Wikipedia(private val context: Context) {

    @Serializable
    data class Article(val title: String, val extract: String, val url: String)

    private val json = Json { ignoreUnknownKeys = true }

    // Parsed on first use, not at startup: nothing on the capture screen needs it, and
    // decoding 2 MB of JSON on the main thread before the viewfinder appears is a stutter
    // the user would feel and never understand.
    private val articles: Map<String, Article> by lazy {
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            json.decodeFromString<Map<String, Article>>(text)
        }.getOrDefault(emptyMap())
    }

    fun article(taxonId: Int): Article? = articles[taxonId.toString()]

    val available: Boolean get() = articles.isNotEmpty()

    companion object {
        const val ASSET = "wikipedia.json"
    }
}
