package dk.lifelist.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cross-language parity — `shared/taxonomy-spec.md` §5.1, test 3.
 *
 * Pure arithmetic over a fixed probability vector: no model, no ONNX session, no
 * emulator. It runs in milliseconds on the JVM, which is why it is written first and
 * why the rollup lives in a pure-Kotlin module. Preprocessing drift and rollup drift
 * both present as "the model is just bad" rather than as an obvious bug, so they get
 * caught here or not at all.
 */
class RollupGoldenTest {

    private val golden = Json.parseToJsonElement(
        File(goldenPath()).readText()
    ).jsonObject

    private fun goldenPath(): String {
        // Resolve from the module dir up to the repo root, so the test works from
        // Gradle, an IDE, or CI without a hardcoded absolute path.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "shared/golden/golden_rollup.json")
            if (candidate.exists()) return candidate.path
            dir = dir.parentFile
        }
        error("golden_rollup.json not found — run training/tools/gen_golden.py")
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

    @Test
    fun `spec version matches`() {
        assertEquals(
            SPEC_VERSION,
            golden["spec_version"]!!.jsonPrimitive.content.toInt(),
            "golden fixture was generated against a different spec version"
        )
    }

    @Test
    fun `every golden case reproduces exactly`() {
        val tax = taxonomy()

        for (case in golden["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val p = c["leaf_probabilities"]!!.jsonArray
                .map { it.jsonPrimitive.content.toFloat() }
                .toFloatArray()
            val threshold = c["threshold"]!!.jsonPrimitive.content.toFloat()
            val expected = c["expected"]!!.jsonObject

            val actual = Rollup.rollup(tax, p, threshold)

            assertEquals(
                expected["taxon_id"]!!.jsonPrimitive.content.toInt(),
                actual.taxonId,
                "[$name] taxon_id"
            )
            assertEquals(
                expected["rank"]!!.jsonPrimitive.content,
                actual.rank,
                "[$name] rank"
            )

            val expectedP = expected["probability"]!!.jsonPrimitive.content.toFloat()
            assertTrue(
                abs(expectedP - actual.probability) < 1e-5f,
                "[$name] probability: expected $expectedP, got ${actual.probability}"
            )

            val expectedCandidates = expected["candidates"]!!.jsonArray
            assertEquals(
                expectedCandidates.size,
                actual.candidates.size,
                "[$name] candidate count"
            )
            expectedCandidates.forEachIndexed { i, element ->
                val e = element.jsonObject
                val a = actual.candidates[i]
                assertEquals(
                    e["taxon_id"]!!.jsonPrimitive.content.toInt(),
                    a.taxonId,
                    "[$name] candidate $i taxon_id — ordering must match Python exactly"
                )
                assertTrue(
                    abs(e["probability"]!!.jsonPrimitive.content.toFloat() - a.probability) < 1e-5f,
                    "[$name] candidate $i probability"
                )
            }
        }
    }

    @Test
    fun `threshold boundary is inclusive`() {
        val tax = taxonomy()
        // Carabus sits at exactly 0.70; spec §4.2 says >= descends.
        val r = Rollup.rollup(tax, floatArrayOf(0.40f, 0.30f, 0.20f, 0.10f), 0.70f)
        assertEquals("genus", r.rank)
    }

    @Test
    fun `stricter threshold never returns a deeper node`() {
        val tax = taxonomy()
        val p = floatArrayOf(0.40f, 0.38f, 0.12f, 0.10f)
        val deep = Rollup.rollup(tax, p, 0.50f)
        val shallow = Rollup.rollup(tax, p, 0.95f)
        assertTrue(tax.isAncestorOrSelf(shallow.taxonId, deep.taxonId))
    }

    @Test
    fun `rollup accuracy does not credit a refusal`() {
        val tax = taxonomy()
        val r = Rollup.rollup(tax, floatArrayOf(0.25f, 0.20f, 0.05f, 0.50f), 0.70f)
        assertTrue(r.isUnidentified)
        assertTrue(!Rollup.isRollupCorrect(tax, r, trueLeafId = 7))
    }
}
