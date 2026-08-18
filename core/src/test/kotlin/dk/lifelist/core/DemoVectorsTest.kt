package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo vectors shipped in `:app` must actually demonstrate something.
 *
 * The first build put 93% of the mass inside *Anas*, so the answer never changed anywhere in
 * the 0.50–0.95 slider range and the slider read as broken. It was not broken; it had nothing
 * to say. This pins the retreat points so that cannot happen again unnoticed.
 *
 * The vectors are duplicated from `dk.lifelist.app.Demo` rather than imported: `:core` must not
 * depend on `:app`, and a test that silently followed a change to the demo would assert nothing.
 */
class DemoVectorsTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta"),
            Taxon(7017, 216, "family", "Nymphalidae"),
            Taxon(1898286, 7017, "genus", "Aglais"),
            Taxon(1898287, 1898286, "species", "Aglais urticae", leafIndex = 0),
            Taxon(1898288, 1898286, "species", "Aglais io", leafIndex = 1),
            Taxon(5602, 216, "family", "Carabidae"),
            Taxon(1036775, 5602, "genus", "Carabus"),
            Taxon(-1036775, 1036775, "species", "Carabus sp.", leafIndex = 2),
            Taxon(1036776, 1036775, "species", "Carabus granulatus", leafIndex = 3),
            Taxon(1036777, 1036775, "species", "Carabus nemoralis", leafIndex = 4),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(2498118, 2986, "genus", "Anas"),
            Taxon(2498036, 2498118, "species", "Anas platyrhynchos", leafIndex = 5),
            Taxon(8214667, 2498118, "species", "Anas crecca", leafIndex = 6),
            Taxon(2498101, 2498118, "species", "Anas acuta", leafIndex = 7),
            Taxon(8996942, 2986, "genus", "Cygnus"),
            Taxon(2498343, 8996942, "species", "Cygnus olor", leafIndex = 8),
        )
    )

    private val cases = mapOf(
        "species" to floatArrayOf(0.94f, 0.03f, 0.005f, 0.005f, 0.005f, 0.01f, 0.003f, 0.001f, 0.001f),
        "genus" to floatArrayOf(0.04f, 0.02f, 0.01f, 0.005f, 0.005f, 0.40f, 0.32f, 0.14f, 0.06f),
        "indeterminate" to floatArrayOf(0.03f, 0.02f, 0.72f, 0.13f, 0.10f, 0f, 0f, 0f, 0f),
        "refusal" to floatArrayOf(0.16f, 0.12f, 0.10f, 0.09f, 0.08f, 0.18f, 0.12f, 0.09f, 0.06f),
    )

    private fun answersAcrossSlider(p: FloatArray): Set<Int> =
        (50..95).map { Rollup.rollup(taxonomy, p, it / 100f).taxonId }.toSet()

    @Test
    fun `every demo vector is a valid distribution`() {
        // Rollup rejects anything that does not sum to 1. A demo vector that does not is a
        // crash on the shutter button, which is exactly how the first attempt failed.
        for ((name, p) in cases) {
            val total = p.sum()
            assertTrue(kotlin.math.abs(total - 1f) < 1e-4, "$name sums to $total, not 1.0")
        }
    }

    @Test
    fun `every demo case changes its answer somewhere in the slider range`() {
        for ((name, p) in cases) {
            assertTrue(
                answersAcrossSlider(p).size > 1,
                "$name returns the same node at every threshold — the slider would read as broken",
            )
        }
    }

    @Test
    fun `the genus case retreats from Anas to Anatidae within the slider range`() {
        val p = cases.getValue("genus")

        assertEquals(2498118, Rollup.rollup(taxonomy, p, 0.70f).taxonId, "Anas at the default")
        assertEquals(2986, Rollup.rollup(taxonomy, p, 0.90f).taxonId, "Anatidae when stricter")
    }

    @Test
    fun `the indeterminate case retreats out of Carabus sp within the slider range`() {
        val p = cases.getValue("indeterminate")

        assertEquals(-1036775, Rollup.rollup(taxonomy, p, 0.70f).taxonId)
        assertTrue(Rollup.rollup(taxonomy, p, 0.80f).taxonId != -1036775)
    }

    @Test
    fun `the refusal case refuses at the default threshold and above`() {
        val p = cases.getValue("refusal")

        for (t in 70..95) {
            assertEquals(0, Rollup.rollup(taxonomy, p, t / 100f).taxonId, "resolved at $t%")
        }
    }

    @Test
    fun `and gives a class-level answer when the user relaxes it`() {
        // Not a flaw. Dropping the slider turns "nothing defensible" into "an insect", which
        // is true, useful, and exactly what an adjustable threshold is for.
        //
        // Asserted at 0.50 rather than at Insecta's exact 0.55: the float32 sum of those five
        // leaves is 0.54999995, so a test written at the boundary would fail on arithmetic
        // rather than on behaviour. Threshold inclusivity is RollupGoldenTest's job.
        assertEquals(216, Rollup.rollup(taxonomy, cases.getValue("refusal"), 0.50f).taxonId)
    }
}
