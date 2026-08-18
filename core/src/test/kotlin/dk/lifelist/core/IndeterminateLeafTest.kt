package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Indeterminate leaves — `shared/taxonomy-spec.md` §1.1a.
 *
 * A genus that carries genus-only observations gets a synthetic `<Name> sp.` child with a
 * **negative** taxon id. The Kotlin side never sees the Python that builds it, so what is
 * tested here is that nothing in this implementation assumes `taxonId > 0` — an assumption
 * that would not fail loudly, it would drop a class and quietly cost accuracy.
 */
class IndeterminateLeafTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(99, 0, "family", "Carabidae"),
            Taxon(10, 99, "genus", "Carabus"),
            Taxon(-10, 10, "species", "Carabus sp.", leafIndex = 0),
            Taxon(1, 10, "species", "Carabus granulatus", leafIndex = 1),
            Taxon(2, 10, "species", "Carabus nemoralis", leafIndex = 2),
        )
    )

    @Test
    fun `a negative taxon id loads and validates`() {
        assertEquals(3, taxonomy.nTaxa)
        assertEquals("Carabus sp.", taxonomy.node(-10).scientificName)
        assertEquals(-10, taxonomy.leafId(0))
    }

    @Test
    fun `the indeterminate leaf is in its genus's subtree`() {
        assertTrue(taxonomy.subtreeLeafIndices(10).contains(0))
        assertTrue(taxonomy.isAncestorOrSelf(10, -10))
    }

    @Test
    fun `mass on the indeterminate leaf is evidence for the genus`() {
        // Undetermined Carabus dominates; the two species split the rest evenly, so no
        // species clears 0.70 but the genus does.
        val p = floatArrayOf(0.60f, 0.20f, 0.20f)

        val result = Rollup.rollup(taxonomy, p, threshold = 0.70f)

        assertEquals(10, result.taxonId)
        assertEquals("genus", result.rank)
    }

    @Test
    fun `a confident indeterminate leaf returns the leaf, not the genus`() {
        val p = floatArrayOf(0.90f, 0.05f, 0.05f)

        val result = Rollup.rollup(taxonomy, p, threshold = 0.70f)

        assertEquals(-10, result.taxonId, "the honest answer is 'a Carabus, undetermined'")
    }

    @Test
    fun `it is not reported as a species-level determination`() {
        // Found by building the result screen: the app said "Confident at species level"
        // about a specimen it had explicitly declined to identify to species.
        val p = floatArrayOf(0.90f, 0.05f, 0.05f)
        val answer = Presentation.present(taxonomy, Rollup.rollup(taxonomy, p, threshold = 0.70f))

        assertEquals(AnswerKind.INDETERMINATE, answer.kind)
        assertTrue(!answer.explanation.contains("species level"))
        assertTrue(answer.explanation.contains("Carabus"))
        assertEquals("genus", answer.rankLabel, "the rank determined, not the synthetic node's own")
    }

    @Test
    fun `an ordinary species is still reported as one`() {
        val p = floatArrayOf(0.02f, 0.95f, 0.03f)
        val answer = Presentation.present(taxonomy, Rollup.rollup(taxonomy, p, threshold = 0.70f))

        assertEquals(AnswerKind.LEAF, answer.kind)
        assertEquals("Confident at species level.", answer.explanation)
    }

    @Test
    fun `presentation never renders it as a bare genus`() {
        val p = floatArrayOf(0.90f, 0.05f, 0.05f)
        val answer = Presentation.present(taxonomy, Rollup.rollup(taxonomy, p, threshold = 0.70f))

        assertEquals("Carabus sp.", Presentation.plain(answer.scientificName))
        assertNotNull(answer.scientificName.firstOrNull { it.text == "sp." && !it.italic })
        assertTrue(answer.scientificName.first { it.text == "Carabus" }.italic)
    }
}
