package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of a life list that make it a collection rather than a log.
 *
 * Firsts, the recent rail, the choice a hedged answer offers, and the species list a record can
 * be settled against later. All four are what turn "the app told me something" into "I added
 * something", and all four are decided here in pure Kotlin rather than in a composable, for the
 * same reason the wording is.
 */
class CollectionTest {

    /** Two ducks, a swan, and the synthetic `Anas sp.` that §1.1a puts under the genus. */
    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(1, 0, "family", "Anatidae"),
            Taxon(2, 1, "genus", "Anas"),
            Taxon(3, 1, "genus", "Cygnus"),
            Taxon(10, 2, "species", "Anas platyrhynchos", vernacularEn = "Mallard", leafIndex = 0),
            Taxon(11, 2, "species", "Anas crecca", vernacularEn = "Teal", leafIndex = 1),
            Taxon(12, 3, "species", "Cygnus olor", vernacularEn = "Mute Swan", leafIndex = 2),
            Taxon(-2, 2, "species", "Anas sp.", leafIndex = 3),
        )
    )

    /** Mallard .41, teal .38, swan .18, `Anas sp.` .03. */
    private val probabilities = floatArrayOf(0.41f, 0.38f, 0.18f, 0.03f)

    private fun record(id: String, taxonId: Int, at: Long) = Record(
        id = id, taxonId = taxonId, observedAt = at, photoPaths = emptyList(),
        threshold = 0.70f, modelVersion = "test", determinedBy = Determiner.MODEL,
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
    fun `a genus answer offers the species under it, strongest first`() {
        val choices = LifeList.choices(taxonomy, probabilities, 2)

        assertEquals(listOf(10, 11), choices.map { it.taxonId })
        assertEquals(0.41f, choices.first().probability)
    }

    @Test
    fun `the synthetic sp leaf is never offered as one of the answers`() {
        // `Anas sp.` *is* the genus-level answer. Listing it among the species to choose
        // between offers the question as one of its own answers.
        assertFalse(LifeList.choices(taxonomy, probabilities, 2).any { it.taxonId < 0 })
    }

    @Test
    fun `a candidate in another branch is never offered`() {
        // Cygnus olor belongs in the full result list (§4.3), but picking it would contradict
        // the answer rather than refine it.
        assertFalse(LifeList.choices(taxonomy, probabilities, 2).any { it.taxonId == 12 })
    }

    @Test
    fun `a lone contender is still worth asking about`() {
        // The bug this replaced: a real identification of Yponomeuta at 71% held exactly one
        // of the global top five and so was offered no question at all, while a family-level
        // answer two taps earlier offered three. Same code, different truncation.
        val choices = LifeList.choices(taxonomy, probabilities, 3)

        assertEquals(listOf(12), choices.map { it.taxonId })
    }

    @Test
    fun `choices come from the whole probability vector, not the top five`() {
        // Every leaf under the node is considered, however far down the global ranking it is.
        val longTail = floatArrayOf(0.02f, 0.60f, 0.36f, 0.02f)

        assertEquals(listOf(11, 10), LifeList.choices(taxonomy, longTail, 2).map { it.taxonId })
    }

    @Test
    fun `a leaf below the floor is not offered`() {
        val lopsided = floatArrayOf(0.97f, 0.001f, 0.02f, 0.009f)

        assertEquals(listOf(10), LifeList.choices(taxonomy, lopsided, 2).map { it.taxonId })
    }

    @Test
    fun `a species answer asks no question`() {
        assertTrue(LifeList.choices(taxonomy, probabilities, 10).isEmpty())
    }

    @Test
    fun `root asks no question`() {
        assertTrue(LifeList.choices(taxonomy, probabilities, ROOT_ID).isEmpty())
    }

    @Test
    fun `choices respect their limit`() {
        assertEquals(1, LifeList.choices(taxonomy, probabilities, 1, limit = 1).size)
        assertEquals(3, LifeList.choices(taxonomy, probabilities, 1, limit = 5).size)
    }

    // -- settling a record by hand, later --------------------------------------

    @Test
    fun `every species under a node can be listed for settling`() {
        assertEquals(
            listOf("Anas sp.", "Mallard", "Teal"),
            LifeList.speciesUnder(taxonomy, 2).map { it.vernacularEn ?: it.scientificName },
        )
    }

    @Test
    fun `a species has nothing under it to settle to`() {
        assertTrue(LifeList.speciesUnder(taxonomy, 10).isEmpty())
    }

    @Test
    fun `picking a choice refines the record and credits the user`() {
        val kept = record("a", 2, 1)
        val refined = LifeList.refine(taxonomy, kept, 10, Determiner.USER)

        assertEquals(10, refined.taxonId)
        assertEquals(Determiner.USER, refined.determinedBy)
        assertEquals(2, refined.refinedFrom, "what the model said is not overwritten")
    }

    @Test
    fun `a record keeps its photographs and its date when it is settled`() {
        // The same sighting, better named. Not a new one.
        val kept = record("a", 2, 1_700_000_000_000).copy(photoPaths = listOf("/a.jpg", "/b.jpg"))
        val refined = LifeList.refine(taxonomy, kept, 11, Determiner.USER)

        assertEquals("a", refined.id)
        assertEquals(1_700_000_000_000, refined.observedAt)
        assertEquals(listOf("/a.jpg", "/b.jpg"), refined.photoPaths)
    }
}
