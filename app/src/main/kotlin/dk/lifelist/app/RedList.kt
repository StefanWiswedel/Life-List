package dk.lifelist.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Den danske Rødliste, as the app uses it: a badge, and a denominator.
 *
 * The denominator is the reason it is here. "12 of 310 Geometridae" needs a number about
 * Denmark, and the Red List records a family for each of 13,899 assessed species, so it can
 * supply one. The categories are the smaller half and mostly say "Least Concern".
 *
 * The licence is one sentence — free for anyone to use as they are, *not without proper
 * citation* — so [citation] travels with the data and the screen that shows a category shows
 * where it came from.
 *
 * Absent in a build that has not run `lifelist-redlist`: every accessor returns null or empty
 * and the screens simply do not draw the row. A missing optional asset is not a crash (§36).
 */
class RedList(private val context: Context) {

    @Serializable
    private data class Document(
        val source: String = "",
        val citation: String = "",
        val url: String = "",
        val fetched: String = "",
        val categories: Map<String, String> = emptyMap(),
        val vernacularDa: Map<String, String> = emptyMap(),
        val familyTotals: Map<String, Int> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    private val document: Document by lazy {
        runCatching {
            val text = context.assets.open("redlist.json").use { it.readBytes().decodeToString() }
            json.decodeFromString<Document>(text)
        }.getOrDefault(Document())
    }

    /** Species per family in Denmark, keyed by scientific name — what `Families` wants. */
    val familyTotals: Map<String, Int> get() = document.familyTotals

    val citation: String? get() = document.citation.ifBlank { null }
    val url: String? get() = document.url.ifBlank { null }
    val available: Boolean get() = document.categories.isNotEmpty()

    /** The raw category, `LC` and all. */
    fun category(taxonId: Int): String? = document.categories[taxonId.toString()]

    /** A Danish name the Red List has and the taxonomy does not. */
    fun danishName(taxonId: Int): String? = document.vernacularDa[taxonId.toString()]

    /**
     * The category, but only when it is worth a line on screen.
     *
     * Three quarters of what the list covers is Least Concern, and `NA`, `NE` and `DD` are
     * statements about the assessment rather than about the animal. A badge that fires on
     * everything is decoration; this one fires on 128 species in the current model.
     */
    fun notable(taxonId: Int): Status? {
        val code = category(taxonId) ?: return null
        val words = NOTABLE[code] ?: return null
        return Status(code, words)
    }

    data class Status(val code: String, val words: String)

    private companion object {
        val NOTABLE = mapOf(
            "RE" to "Regionally extinct in Denmark",
            "CR" to "Critically endangered in Denmark",
            "EN" to "Endangered in Denmark",
            "VU" to "Vulnerable in Denmark",
            "NT" to "Near threatened in Denmark",
        )
    }
}
