package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of a life list that make it a collection rather than a log.
 *
 * Firsts, the recent rail, and the choice a hedged answer offers. All three are what turn
 * "the app told me something" into "I added something", and all three are decided here in
 * pure Kotlin rather than in a composable, for the same reason the wording is.
 */
class CollectionTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(1, 0, "family", "Anatidae"),
            Taxon(2, 1, "genus", "Anas"),
            Taxon(3, 1, "genus", "Cygnus"),
            Taxon(10, 2, "species", "Anas platyrhynchos", vernacularEn = "Mallard", leafIndex = 0),
            Taxon(11, 2, "species", "Anas crecca", vernacularEn = "Teal", leafIndex = 1),
            Taxon(12, 3, "species", "Cygnus olor", vernacularEn = "Mute Swan", leafIndex = 2),
        )
    )

    private fun record(id: String, taxonId: Int, at: Long) = Record(
        id = id, taxonId = taxonId, observedAt = at, photoPath = null,
        threshold = 0.70f, modelVersion = "test", determinedBy = Determiner.MODEL,
    )

    private fun result(taxonId: Int, rank: String) = RollupResult(
        taxonId = taxonId,
        rank = rank,
        probability = 0.88f,
        candidates = listOf(
            Candidate(10, 0, 0.41f),
            Candidate(11, 1, 0.38f),
            Candidate(12, 2, 0.21f),
        ),
        threshold = 0.70f,
    )

    // -- firsts -----------------------------------------------------------------

    @Test
    fun `a taxon never recorded before is a first`() {
        assertTrue(LifeList.isFirst(emptyList(), 10))
        assertTrue(LifeList.isFirst(listOf(record("a", 11, 1)), 10))
    }

    @Test
    fun `the second sighting of the same taxon is not`() {
        assertFalse(LifeList.isFirst(listOf(record("a", 10, 1)), 10))
    }

    @Test
    fun `a first is per taxon, not per genus`() {
        // A mallard after a teal is a first. Making the badge rarer by counting the genus
        // would be flattering the list rather than describing it.
        assertTrue(LifeList.isFirst(listOf(record("a", 11, 1)), 10))
    }

    @Test
    fun `a genus record does not spend the species' first`() {
        assertTrue(LifeList.isFirst(listOf(record("a", 2, 1)), 10))
    }

    // -- the recent rail --------------------------------------------------------

    @Test
    fun `recent runs newest first`() {
        val records = listOf(record("a", 10, 100), record("b", 11, 300), record("c", 12, 200))

        assertEquals(listOf("b", "c", "a"), LifeList.recent(records).map { it.id })
    }

    @Test
    fun `recent shows one photograph per taxon, the newest`() {
        // Six pictures of the same blackbird is a rail that says nothing.
        val records = listOf(record("old", 10, 100), record("new", 10, 500), record("other", 11, 300))

        assertEquals(listOf("new", "other"), LifeList.recent(records).map { it.id })
    }

    @Test
    fun `recent honours its limit`() {
        val records = (1..10).map { record("r$it", if (it % 2 == 0) 10 else 11, it.toLong()) }

        assertEquals(2, LifeList.recent(records, limit = 5).size) // only two distinct taxa
        assertEquals(1, LifeList.recent(records, limit = 1).size)
    }

    // -- the choice a hedge offers ---------------------------------------------

    @Test
    fun `a genus answer offers the species under it`() {
        val choices = LifeList.choices(taxonomy, result(2, "genus"))

        assertEquals(listOf(10, 11), choices.map { it.taxonId })
    }

    @Test
    fun `a candidate in another branch is never offered as a choice`() {
        // Cygnus olor is worth showing in the full candidate list (§4.3), but picking it
        // would not refine the answer — it would contradict it.
        val choices = LifeList.choices(taxonomy, result(2, "genus"))

        assertFalse(choices.any { it.taxonId == 12 })
    }

    @Test
    fun `a species answer asks no question`() {
        assertTrue(LifeList.choices(taxonomy, result(10, "species")).isEmpty())
    }

    @Test
    fun `an unidentified result asks no question`() {
        assertTrue(LifeList.choices(taxonomy, result(0, "root")).isEmpty())
    }

    @Test
    fun `a node with only one leaf under it asks no question`() {
        // "Which one is it?" with one answer is not a question, it is a nag.
        val single = result(3, "genus")

        assertTrue(LifeList.choices(taxonomy, single).isEmpty())
    }

    @Test
    fun `choices come back strongest first and respect the limit`() {
        val choices = LifeList.choices(taxonomy, result(1, "family"), limit = 2)

        assertEquals(listOf(10, 11), choices.map { it.taxonId })
        assertEquals(0.41f, choices.first().probability)
    }

    // -- picking one is a refinement, and is recorded as the user's ------------

    @Test
    fun `picking a choice refines the record and credits the user`() {
        val kept = record("a", 2, 1)
        val refined = LifeList.refine(taxonomy, kept, 10, Determiner.USER)

        assertEquals(10, refined.taxonId)
        assertEquals(Determiner.USER, refined.determinedBy)
        assertEquals(2, refined.refinedFrom, "what the model said is not overwritten")
    }
}
