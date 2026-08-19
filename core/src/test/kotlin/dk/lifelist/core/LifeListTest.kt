package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The counting rules, which are where a life list quietly becomes a leaderboard.
 */
class LifeListTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta"),
            Taxon(5602, 216, "family", "Carabidae"),
            Taxon(1036775, 5602, "genus", "Carabus"),
            Taxon(-1036775, 1036775, "species", "Carabus sp.", leafIndex = 0),
            Taxon(1036776, 1036775, "species", "Carabus granulatus", leafIndex = 1),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(2498036, 2986, "species", "Anas platyrhynchos", leafIndex = 2),
        )
    )

    private fun record(id: String, taxonId: Int, by: Determiner = Determiner.MODEL) =
        Record(id, taxonId, 1_787_000_000_000L, emptyList(), 0.70f, "test", by)

    // -- grouping ---------------------------------------------------------------

    @Test
    fun `a beetle is an insect and a duck is a bird`() {
        assertEquals("Insects", LifeList.groupOf(taxonomy, 1036776))
        assertEquals("Birds", LifeList.groupOf(taxonomy, 2498036))
    }

    @Test
    fun `a record kept at genus still lands in its group`() {
        assertEquals("Insects", LifeList.groupOf(taxonomy, 1036775))
    }

    @Test
    fun `something outside every configured group is Other, not dropped`() {
        assertEquals(UNGROUPED, LifeList.groupOf(taxonomy, 0))
    }

    @Test
    fun `empty groups are kept so the list can say what is missing`() {
        val tally = LifeList.tally(taxonomy, listOf(record("a", 1036776)))

        assertTrue(tally.any { it.label == "Amphibians" && it.records.isEmpty() })
    }

    @Test
    fun `groups are ordered by size`() {
        val records = listOf(record("a", 1036776), record("b", -1036775), record("c", 2498036))

        val labels = LifeList.tally(taxonomy, records).map { it.label }

        assertEquals("Insects", labels.first())
    }

    // -- counting honestly ------------------------------------------------------

    @Test
    fun `a genus record counts as a record but not as a species`() {
        val totals = LifeList.totals(taxonomy, listOf(record("a", 1036775)))

        assertEquals(1, totals.records)
        assertEquals(0, totals.toSpecies)
        assertEquals(1, totals.coarser)
    }

    @Test
    fun `an indeterminate leaf is not a species tick either`() {
        // `Carabus sp.` is structurally a leaf. Counting it as a species would be the
        // overclaim this whole app exists to avoid, arriving through the back door.
        val totals = LifeList.totals(taxonomy, listOf(record("a", -1036775)))

        assertEquals(1, totals.records)
        assertEquals(0, totals.toSpecies)
    }

    @Test
    fun `ten photos of one mallard is one taxon`() {
        val records = (1..10).map { record("r$it", 2498036) }

        assertEquals(10, LifeList.totals(taxonomy, records).records)
        assertEquals(1, LifeList.totals(taxonomy, records).taxa)
    }

    // -- browsing ---------------------------------------------------------------

    @Test
    fun `browsing a family shows both the species record and the genus one`() {
        val records = listOf(record("sp", 1036776), record("gen", 1036775))

        val under = LifeList.under(taxonomy, records, 5602)

        assertEquals(setOf("sp", "gen"), under.map { it.id }.toSet())
    }

    @Test
    fun `browsing a genus does not show a bird`() {
        val records = listOf(record("beetle", 1036776), record("duck", 2498036))

        assertEquals(listOf("beetle"), LifeList.under(taxonomy, records, 1036775).map { it.id })
    }

    // -- refinement -------------------------------------------------------------

    @Test
    fun `a genus record refines to a species below it and keeps its history`() {
        val original = record("a", 1036775)

        val refined = LifeList.refine(taxonomy, original, 1036776, Determiner.USER)

        assertEquals(1036776, refined.taxonId)
        assertEquals(1036775, refined.refinedFrom)
        assertEquals(Determiner.USER, refined.determinedBy)
    }

    @Test
    fun `refining upward is refused`() {
        val original = record("a", 1036776)

        assertFailsWith<IllegalArgumentException> {
            LifeList.refine(taxonomy, original, 1036775, Determiner.USER)
        }
    }

    @Test
    fun `refining sideways to another branch is refused`() {
        val original = record("a", 1036775)

        assertFailsWith<IllegalArgumentException> {
            LifeList.refine(taxonomy, original, 2498036, Determiner.USER)
        }
    }

    @Test
    fun `a user determination is recorded as the user's`() {
        val refined = LifeList.refine(taxonomy, record("a", 1036775), 1036776, Determiner.USER)

        assertEquals(Determiner.USER, refined.determinedBy)
    }
}
