package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A record whose taxon this tree has never heard of.
 *
 * v0.7.1 crashed on launch for anyone holding a single saved sighting. The immediate cause was
 * `MainActivity` handing the home screen the nineteen-node *demo* taxonomy while the real one
 * loaded; `Taxonomy.node` is `nodes.getValue`, which throws, so the first frame died and every
 * launch after it died the same way. Deleting the app's data was the only way out.
 *
 * The wiring bug is fixed elsewhere. This file is about the thing underneath it, which would
 * have bitten eventually anyway: **a life list is permanent and a taxonomy is a build
 * artefact.** Retrain at a different occurrence threshold and taxa leave the tree. Every record
 * of a departed taxon then points at nothing, and a collection must not be destroyed by the
 * model changing under it.
 *
 * So: everything that reads a *stored* taxon id degrades. Everything that reads an id the
 * rollup just produced still throws, because there a miss is a broken invariant.
 */
class OrphanedRecordTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(212, 0, "class", "Aves", vernacularEn = "Birds"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(10, 2986, "species", "Anas platyrhynchos", vernacularEn = "Mallard", leafIndex = 0),
            Taxon(11, 2986, "species", "Anas crecca", vernacularEn = "Teal", leafIndex = 1),
        )
    )

    /** 1688020 is a real Leptophyes id, and deliberately not in the tree above. */
    private val orphan = 1688020

    private fun record(id: String, taxonId: Int) = Record(
        id = id, taxonId = taxonId, observedAt = 1_755_000_000_000, photoPaths = emptyList(),
        threshold = 0.70f, modelVersion = "old", determinedBy = Determiner.MODEL,
    )

    // -- the lookup itself ------------------------------------------------------

    @Test
    fun `the strict lookup still throws, because the rollup depends on it`() {
        assertFailsWith<NoSuchElementException> { taxonomy.node(orphan) }
    }

    @Test
    fun `the tolerant lookup returns null`() {
        assertNull(taxonomy.nodeOrNull(orphan))
        assertNotNull(taxonomy.nodeOrNull(10))
    }

    @Test
    fun `membership can be asked before assuming`() {
        assertTrue(orphan !in taxonomy)
        assertTrue(10 in taxonomy)
    }

    @Test
    fun `a lineage for an unknown taxon is empty rather than fatal`() {
        assertEquals(emptyList(), taxonomy.lineage(orphan))
    }

    @Test
    fun `ancestry involving an unknown taxon is false rather than fatal`() {
        assertEquals(false, taxonomy.isAncestorOrSelf(0, orphan))
        assertEquals(false, taxonomy.isAncestorOrSelf(orphan, 10))
    }

    // -- the screens that killed the app ---------------------------------------

    @Test
    fun `totals survive a record the taxonomy has lost`() {
        // This is the exact call the home screen makes on its first frame.
        val totals = LifeList.totals(taxonomy, listOf(record("a", 10), record("b", orphan)))

        assertEquals(2, totals.records)
        assertEquals(1, totals.toSpecies, "an unknown taxon is not a species tick")
        assertEquals(1, totals.coarser)
    }

    @Test
    fun `grouping survives, and files the orphan where it can be found`() {
        val tallies = LifeList.tally(taxonomy, listOf(record("a", 10), record("b", orphan)))

        assertEquals(1, tallies.single { it.label == "Birds" }.records.size)
        assertEquals(1, tallies.single { it.label == UNGROUPED }.records.size)
    }

    @Test
    fun `the record is kept, not quietly dropped`() {
        // Hiding it would look like the app had eaten a sighting, which is its own kind of bug.
        val tallies = LifeList.tally(taxonomy, listOf(record("b", orphan)))

        assertEquals(1, tallies.sumOf { it.records.size })
    }

    @Test
    fun `an orphan offers no choices and nothing to settle to`() {
        assertTrue(LifeList.choices(taxonomy, floatArrayOf(0.6f, 0.4f), orphan).isEmpty())
        assertTrue(LifeList.speciesUnder(taxonomy, orphan).isEmpty())
    }

    @Test
    fun `refining to a taxon this tree does not have is refused, not attempted`() {
        assertFailsWith<IllegalArgumentException> {
            LifeList.refine(taxonomy, record("a", 2986), orphan, Determiner.USER)
        }
    }

    @Test
    fun `recent and firsts never needed the taxonomy and still do not`() {
        val records = listOf(record("a", orphan))

        assertEquals(listOf("a"), LifeList.recent(records).map { it.id })
        assertTrue(!LifeList.isFirst(records, orphan))
    }
}
