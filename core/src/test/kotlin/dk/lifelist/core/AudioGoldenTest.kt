package dk.lifelist.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cross-language parity for audio — `shared/taxonomy-spec.md` §4A.
 *
 * The sibling of [RollupGoldenTest], guarding the parts §4 does not have: which classes land in
 * a confusion set, and whether the geographic prior is applied before or after that set is
 * built. The second is the one that matters — applying it first is a two-line mistake that turns
 * a prior into a silent mask, and nothing in the rollup fixture would notice.
 */
class AudioGoldenTest {

    private val golden = Json.parseToJsonElement(File(goldenPath()).readText()).jsonObject

    private fun goldenPath(): String {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "shared/golden/golden_audio.json")
            if (candidate.exists()) return candidate.path
            dir = dir.parentFile
        }
        error("golden_audio.json not found — run training/tools/gen_golden_audio.py")
    }

    private fun taxonomy(): Taxonomy {
        val taxaJson = golden["taxonomy"]!!.jsonObject["taxa"]!!.jsonArray
        return Taxonomy(
            taxaJson.map { element ->
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
        )
    }

    private fun scores(element: kotlinx.serialization.json.JsonElement?): Map<Int, Float>? {
        if (element == null || element is JsonNull) return null
        return element.jsonObject.entries.associate {
            it.key.toInt() to it.value.jsonPrimitive.content.toFloat()
        }
    }

    @Test
    fun `spec version matches`() {
        assertEquals(
            SPEC_VERSION,
            golden["spec_version"]!!.jsonPrimitive.content.toInt(),
            "golden audio fixture was generated against a different spec version"
        )
    }

    @Test
    fun `every golden case reproduces exactly`() {
        val tax = taxonomy()

        for (case in golden["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content

            val actual = Audio.identifyWindow(
                tax,
                scores = scores(c["scores"])!!,
                threshold = c["threshold"]!!.jsonPrimitive.content.toFloat(),
                detectionThreshold = c["detection_threshold"]!!.jsonPrimitive.content.toFloat(),
                margin = c["margin"]!!.jsonPrimitive.content.toFloat(),
                geo = scores(c["geo"]),
                geoWeight = c["geo_weight"]!!.jsonPrimitive.content.toFloat(),
            )

            val expected = c["expected"]!!.jsonArray
            assertEquals(expected.size, actual.size, "[$name] detection count")

            expected.forEachIndexed { i, element ->
                val e = element.jsonObject
                val a = actual[i]
                val where = "[$name] detection $i"

                assertEquals(
                    e["detection_taxon_id"]!!.jsonPrimitive.content.toInt(),
                    a.detection.taxonId,
                    "$where: detection order"
                )
                assertEquals(
                    e["confusion_set"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() },
                    a.confusionSet,
                    "$where: confusion set"
                )
                assertEquals(
                    e["geo_applied"]!!.jsonPrimitive.content.toBooleanStrict(),
                    a.geoApplied,
                    "$where: geo_applied"
                )
                assertEquals(
                    e["taxon_id"]!!.jsonPrimitive.content.toInt(),
                    a.result.taxonId,
                    "$where: taxon_id"
                )
                assertEquals(
                    e["rank"]!!.jsonPrimitive.content,
                    a.result.rank,
                    "$where: rank"
                )

                val expectedP = e["probability"]!!.jsonPrimitive.content.toFloat()
                assertTrue(
                    abs(expectedP - a.result.probability) < 1e-5f,
                    "$where: probability expected $expectedP, got ${a.result.probability}"
                )

                // The raw, pre-prior scores must survive intact — spec §4A.4 stores them so a
                // suppressed vagrant stays recoverable, and a prior applied one line too early
                // would show up here as well as in the confusion set.
                val expectedRaw = e["raw_scores"]!!.jsonObject
                assertEquals(expectedRaw.size, a.rawScores.size, "$where: raw score count")
                for ((key, value) in expectedRaw) {
                    val got = a.rawScores.getValue(key.toInt())
                    assertTrue(
                        abs(value.jsonPrimitive.content.toFloat() - got) < 1e-5f,
                        "$where: raw score $key"
                    )
                }
            }
        }
    }
}
