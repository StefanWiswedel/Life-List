package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "I want to be right 95% of the time" — what that costs per group, and what it cannot buy.
 */
class CertaintyTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(2498036, 2986, "species", "Anas platyrhynchos", leafIndex = 0),
            Taxon(2498037, 2986, "species", "Anas crecca", leafIndex = 1),
            Taxon(359, 0, "class", "Mammalia"),
            Taxon(5219, 359, "family", "Cervidae"),
            Taxon(2440947, 5219, "species", "Capreolus capreolus", leafIndex = 2),
            Taxon(358, 0, "class", "Reptilia"),
            Taxon(6162, 358, "family", "Colubridae"),
            Taxon(5225204, 6162, "species", "Natrix natrix", leafIndex = 3),
        )
    )

    private val table = CertaintyTable(
        mapOf(
            0.90f to mapOf(
                "Birds" to GroupThreshold(0.64f, 0.905f, reached = true, n = 3387),
                "Mammals" to GroupThreshold(0.92f, 0.897f, reached = false, n = 497),
            ),
            0.95f to mapOf(
                "Birds" to GroupThreshold(0.82f, 0.954f, reached = true, n = 3387),
                "Mammals" to GroupThreshold(0.92f, 0.897f, reached = false, n = 497),
            ),
        )
    )

    private fun probabilities(vararg p: Float) = floatArrayOf(*p)

    // -- which threshold ---------------------------------------------------------

    @Test
    fun `the same target costs each group a different threshold`() {
        assertEquals(0.82f, table.certaintyFor(0.95f, "Birds").threshold)
        assertEquals(0.92f, table.certaintyFor(0.95f, "Mammals").threshold)
    }

    @Test
    fun `the group comes from the prediction, not from the truth`() {
        // A duck photograph: the top leaf is Anas platyrhynchos, so this is a bird question and
        // must be answered at a bird's threshold. At the moment of choosing, the truth is what
        // is unknown — splitting on it would measure the model under conditions never met.
        val duck = probabilities(0.6f, 0.2f, 0.15f, 0.05f)

        assertEquals("Birds", Certainties.predictedGroup(taxonomy, duck))
        assertEquals(0.82f, Certainties.rollup(taxonomy, duck, table, 0.95f).certainty.threshold)
    }

    @Test
    fun `a roe deer photograph is answered at the mammal threshold`() {
        val deer = probabilities(0.1f, 0.1f, 0.75f, 0.05f)

        val targeted = Certainties.rollup(taxonomy, deer, table, 0.95f)

        assertEquals("Mammals", targeted.certainty.group)
        assertEquals(0.92f, targeted.certainty.threshold)
        // 0.75 does not clear 0.92 even summed at the class, so the app declines to answer.
        // At a bird's 0.82 it would not have either — which is the point: the price of asking
        // for 95% on the worst-calibrated group in the set is more refusals, and saying so is
        // more use than a confident roe deer that might be a fallow.
        assertEquals(taxonomy.rootId, targeted.result.taxonId)
        assertTrue(targeted.result.isUnidentified)
    }

    @Test
    fun `the chosen threshold is the one the rollup actually used`() {
        val duck = probabilities(0.85f, 0.05f, 0.05f, 0.05f)

        val targeted = Certainties.rollup(taxonomy, duck, table, 0.95f)

        assertEquals(Rollup.rollup(taxonomy, duck, 0.82f).taxonId, targeted.result.taxonId)
    }

    // -- what the app may claim --------------------------------------------------

    @Test
    fun `a target the model cannot reach is reported as unreached`() {
        val certainty = table.certaintyFor(0.95f, "Mammals")

        assertFalse(certainty.reached)
        assertEquals(0.897f, certainty.delivered)
        assertTrue(certainty.measured)
    }

    @Test
    fun `an unmeasured group falls back to the global threshold and says so`() {
        // Reptiles had fewer than 100 test photographs, so no threshold was fitted. A number
        // invented from forty examples would be noise wearing calibration's clothes.
        val snake = probabilities(0.05f, 0.05f, 0.1f, 0.8f)

        val targeted = Certainties.rollup(taxonomy, snake, table, 0.95f)

        assertEquals("Reptiles", targeted.certainty.group)
        assertEquals(DEFAULT_THRESHOLD, targeted.certainty.threshold)
        assertFalse(targeted.certainty.measured)
    }

    @Test
    fun `an empty table leaves every group on the global threshold`() {
        val duck = probabilities(0.6f, 0.2f, 0.15f, 0.05f)

        val targeted = Certainties.rollup(taxonomy, duck, CertaintyTable.EMPTY, 0.95f)

        assertEquals(DEFAULT_THRESHOLD, targeted.certainty.threshold)
        assertFalse(targeted.certainty.measured)
    }

    // -- a preference that outlives the model ------------------------------------

    @Test
    fun `a target the table does not carry resolves upward, never down`() {
        // A phone holding 0.98 against a table fitted at 0.90 and 0.95 must not quietly become
        // less careful than the person asked for.
        assertEquals(0.95f, table.resolveTarget(0.98f))
        assertEquals(0.90f, table.resolveTarget(0.85f))
        assertEquals(0.90f, table.resolveTarget(0.90f))
    }

    @Test
    fun `the targets on offer are the ones that were fitted`() {
        assertEquals(listOf(0.90f, 0.95f), table.targets)
    }
}
