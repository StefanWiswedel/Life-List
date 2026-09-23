package dk.lifelist.app

import android.content.Context
import dk.lifelist.core.Checklist
import dk.lifelist.core.ChecklistFamily
import dk.lifelist.core.ChecklistSpecies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Denmark's checklist, off the disk.
 *
 * 4.7 MB of JSON and 26,722 species, so it is read **once, lazily, off the main thread** by the
 * caller and then held. Not at launch: the index is a screen you visit, and paying five
 * megabytes of parsing for somebody who only ever presses the camera button is the kind of
 * cold-start cost that gets blamed on the 335 MB model.
 *
 * Absent is a state, not a crash. A build without the asset has an empty checklist, every
 * screen that reads it shows nothing rather than half of something, and the app still
 * identifies — which is the same rule the reference photos and recordings follow (§76).
 */
class ChecklistAssets(private val context: Context) {

    @Serializable
    private data class Document(
        val country: String = "",
        val fetched: String = "",
        val minRecords: Int = 0,
        val families: Map<String, Family> = emptyMap(),
        val species: Map<String, Species> = emptyMap(),
    )

    @Serializable
    private data class Family(
        val name: String = "",
        val vernacularEn: String? = null,
        val lineage: List<Int> = emptyList(),
        val species: Int = 0,
        val model: Int = 0,
    )

    @Serializable
    private data class Species(
        val name: String = "",
        val vernacularEn: String? = null,
        val family: Int? = null,
        val records: Int = 0,
        val model: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    /**
     * Parsed on first touch.
     *
     * `by lazy` rather than a field: the first read is a second of work and belongs on whatever
     * background thread asked for it, not on whichever one happened to build this object.
     */
    val checklist: Checklist by lazy {
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            val document = json.decodeFromString<Document>(text)
            Checklist(
                families = document.families.mapNotNull { (key, family) ->
                    val id = key.toIntOrNull() ?: return@mapNotNull null
                    id to ChecklistFamily(
                        taxonId = id,
                        scientificName = family.name,
                        vernacularEn = family.vernacularEn,
                        lineage = family.lineage,
                        species = family.species,
                        identifiable = family.model,
                    )
                }.toMap(),
                species = document.species.mapNotNull { (key, species) ->
                    val id = key.toIntOrNull() ?: return@mapNotNull null
                    id to ChecklistSpecies(
                        taxonId = id,
                        scientificName = species.name,
                        vernacularEn = species.vernacularEn,
                        familyId = species.family,
                        records = species.records,
                        identifiable = species.model,
                    )
                }.toMap(),
            )
        }.getOrDefault(Checklist.EMPTY)
    }

    /** True when this build shipped a checklist at all. */
    val available: Boolean get() = checklist.species.isNotEmpty()

    companion object {
        const val ASSET = "checklist.json"
    }
}

/**
 * Which of a family's species wears its face.
 *
 * The most-recorded one in Denmark, deterministically. It has to be the same picture every
 * time — a hero that changes between visits is a page you cannot learn — and "commonest" is
 * the member most likely to be the one somebody already recognises.
 */
fun heroOf(checklist: Checklist, familyId: Int): Int =
    checklist.membersOf(familyId)
        .filter { it.identifiable }
        .maxByOrNull { it.records }
        ?.taxonId
        ?: checklist.membersOf(familyId).maxByOrNull { it.records }?.taxonId
        ?: familyId
