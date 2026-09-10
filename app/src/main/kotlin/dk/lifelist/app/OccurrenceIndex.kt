package dk.lifelist.app

import android.content.Context
import dk.lifelist.core.Occurrence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reading `occurrences.json` — how often each species is recorded in Denmark (§72).
 *
 * Absent is fine: a build without the file simply shows no line, which is better than a build
 * that will not start. The same posture the Red List data has had since §55.
 */
class OccurrenceIndex(private val context: Context) {

    @Serializable
    private data class Row(val n: Int, val rank: Int, val of: Int)

    @Serializable
    private data class Document(val taxa: Map<String, Row> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private val rows: Map<String, Row> by lazy {
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            json.decodeFromString<Document>(text).taxa
        }.getOrDefault(emptyMap())
    }

    fun forTaxon(taxonId: Int): Occurrence? =
        rows[taxonId.toString()]?.let { Occurrence(taxonId, it.n, it.rank, it.of) }

    val available: Boolean by lazy { rows.isNotEmpty() }

    companion object {
        const val ASSET = "occurrences.json"
    }
}
