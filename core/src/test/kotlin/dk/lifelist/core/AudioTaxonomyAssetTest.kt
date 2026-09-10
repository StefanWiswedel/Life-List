package dk.lifelist.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shipped audio taxonomy, loaded by the Kotlin that will load it on the phone.
 *
 * Python built this file and Python's `Taxonomy` validated it — but Python's validation is not
 * the one that runs on a device, and a spec invariant the two disagree about would surface as a
 * crash on launch rather than as a failing test. §36 is the precedent: an asset problem that
 * only appeared once somebody had a saved record.
 *
 * It also pins the numbers §62 measured, so a rebuild that quietly halves the tree is a failing
 * test rather than a smaller APK nobody looks at.
 */
class AudioTaxonomyAssetTest {

    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found — run lifelist-birdnet --commit")
    }

    private val taxonomy: Taxonomy by lazy {
        val taxa = Json.parseToJsonElement(
            repoFile("shared/model/audio_taxonomy.json").readText()
        ).jsonArray.map { element ->
            val o = element.jsonObject
            fun intOrNull(key: String) =
                o[key]?.jsonPrimitive?.takeIf { it.content != "null" }?.content?.toIntOrNull()
            fun stringOrNull(key: String) =
                o[key]?.jsonPrimitive?.takeIf { it.content != "null" }?.content

            Taxon(
                taxonId = o["taxon_id"]!!.jsonPrimitive.content.toInt(),
                parentId = intOrNull("parent_id"),
                rank = o["rank"]!!.jsonPrimitive.content,
                scientificName = o["scientific_name"]!!.jsonPrimitive.content,
                vernacularDa = stringOrNull("vernacular_da"),
                vernacularEn = stringOrNull("vernacular_en"),
                leafIndex = intOrNull("leaf_index"),
            )
        }
        // The constructor asserts every spec §1.1 invariant, so this line is the test.
        Taxonomy(taxa)
    }

    @Test
    fun `the shipped audio taxonomy satisfies every invariant Kotlin checks`() {
        assertEquals(795, taxonomy.nTaxa, "leaf count moved; see VERIFICATION.md §62")
        assertTrue(taxonomy.nodes.size > 1200)
    }

    @Test
    fun `every class in the shipped map names a leaf of that taxonomy`() {
        // The two artefacts are written together and read together. A class pointing at a taxon
        // the tree does not contain would throw at the first detection of that species — on a
        // phone, in a field, in the middle of a session.
        val classes = Json.parseToJsonElement(
            repoFile("shared/model/birdnet_classes.json").readText()
        ).jsonObject["classes"]!!.jsonObject

        assertEquals(801, classes.size, "BirdNET's class count moved")
        for ((index, value) in classes) {
            val taxonId = value.jsonPrimitive.content.toInt()
            val node = taxonomy.nodeOrNull(taxonId)
            assertNotNull(node, "class $index names taxon $taxonId, which is not in the tree")
            assertTrue(node.isLeaf, "class $index names $taxonId, which is not a leaf")
        }
    }

    @Test
    fun `the birds a Danish garden actually has are in there, by their English names`() {
        val wanted = mapOf(
            2493091 to "Common Chiffchaff",
            2490719 to "Common Blackbird",
        )
        for ((taxonId, name) in wanted) {
            val node = taxonomy.nodeOrNull(taxonId)
            assertNotNull(node, "$name ($taxonId) is missing from the audio taxonomy")
            assertEquals(name, node.vernacularEn)
        }
    }

    @Test
    fun `no species is in both trees under two different ids`() {
        // One life list, one bird. `Oenanthe seebohmi` resolved to the *subspecies* Oenanthe
        // oenanthe seebohmi and was stored as a leaf named "Oenanthe oenanthe" keyed 5845303,
        // while the vision tree already had that bird as 5231240 — so a wheatear photographed
        // and a wheatear heard were two entries (VERIFICATION §67). Sharing an id is what makes
        // the two trees one list, and a name with two ids quietly breaks that.
        val visionByName = Json.parseToJsonElement(
            repoFile("shared/model/taxonomy.json").readText()
        ).jsonArray.groupBy(
            { it.jsonObject["scientific_name"]!!.jsonPrimitive.content },
            { it.jsonObject["taxon_id"]!!.jsonPrimitive.content.toInt() },
        )

        val clashes = taxonomy.nodes.values.mapNotNull { node ->
            val ids = visionByName[node.scientificName] ?: return@mapNotNull null
            if (node.taxonId in ids) null else "${node.scientificName}: ${node.taxonId} vs $ids"
        }

        assertTrue(clashes.isEmpty(), "the two trees name the same species twice: $clashes")
    }

    @Test
    fun `audio and vision agree about the taxa they share`() {
        // Both trees are keyed by GBIF, which is what lets one life list hold records from
        // both. If they disagreed about a taxon's rank or name, the same bird would read
        // differently depending on how it was found.
        val visionTaxa = Json.parseToJsonElement(
            repoFile("shared/model/taxonomy.json").readText()
        ).jsonArray.associate { element ->
            val o = element.jsonObject
            o["taxon_id"]!!.jsonPrimitive.content.toInt() to
                o["scientific_name"]!!.jsonPrimitive.content
        }

        var shared = 0
        for ((taxonId, node) in taxonomy.nodes) {
            val visionName = visionTaxa[taxonId] ?: continue
            shared++
            assertEquals(visionName, node.scientificName, "taxon $taxonId is named twice")
        }
        assertTrue(shared > 200, "expected the two trees to overlap; they share $shared taxa")
    }
}
