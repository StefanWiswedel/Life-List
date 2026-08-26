package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "12 of 310 Geometridae" — where the numerator and the denominator come from, and why they
 * have to be the same kind of thing.
 */
class FamiliesTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta"),
            Taxon(5602, 216, "family", "Carabidae"),
            Taxon(1036775, 5602, "genus", "Carabus"),
            Taxon(-1036775, 1036775, "species", "Carabus sp.", leafIndex = 0),
            Taxon(1036776, 1036775, "species", "Carabus granulatus", leafIndex = 1),
            Taxon(1036777, 1036775, "species", "Carabus nemoralis", leafIndex = 2),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae", vernacularEn = "Ducks, geese and swans"),
            Taxon(2498036, 2986, "species", "Anas platyrhynchos", leafIndex = 3),
        )
    )

    private val denmark = mapOf("Carabidae" to 320, "Anatidae" to 65)

    private fun record(id: String, taxonId: Int) =
        Record(
            id = id,
            taxonId = taxonId,
            observedAt = 1_787_000_000_000L,
            threshold = 0.70f,
            modelVersion = "test",
            determinedBy = Determiner.MODEL,
        )

    // -- the denominator --------------------------------------------------------

    @Test
    fun `the total is Denmark's when the Red List has one`() {
        val progress = Families.progressFor(taxonomy, listOf(record("a", 1036776)), 1036776, denmark)!!

        assertEquals(320, progress.total)
        assertEquals(Families.Source.DENMARK, progress.source)
    }

    @Test
    fun `without a Danish total it falls back to what the app knows, and says so`() {
        val progress = Families.progressFor(taxonomy, listOf(record("a", 1036776)), 1036776)!!

        assertEquals(3, progress.total, "three Carabidae leaves, including Carabus sp.")
        assertEquals(Families.Source.APP, progress.source)
    }

    @Test
    fun `a Danish total smaller than our own vocabulary does not shrink the denominator`() {
        // The Red List lists 19 Plantaginaceae where the model recognises 31. A denominator
        // below the numerator is worse than one that undersells the country.
        val progress = Families.progressFor(
            taxonomy, listOf(record("a", 1036776)), 1036776, mapOf("Carabidae" to 1)
        )!!

        assertEquals(3, progress.total)
        assertEquals(Families.Source.APP, progress.source)
    }

    // -- the numerator ----------------------------------------------------------

    @Test
    fun `only species count, because only species are in the denominator`() {
        val records = listOf(
            record("a", 1036776),      // Carabus granulatus — counts
            record("b", 1036775),      // kept at genus — real, but not one of the 320
            record("c", -1036775),     // Carabus sp. — a leaf, but not a species Denmark lists
        )

        assertEquals(1, Families.seenIn(taxonomy, records, 5602))
    }

    @Test
    fun `the same species twice is one species`() {
        val records = listOf(record("a", 1036776), record("b", 1036776))

        assertEquals(1, Families.seenIn(taxonomy, records, 5602))
    }

    @Test
    fun `a species from another family is not counted`() {
        val records = listOf(record("a", 1036776), record("b", 2498036))

        assertEquals(1, Families.seenIn(taxonomy, records, 5602))
        assertEquals(1, Families.seenIn(taxonomy, records, 2986))
    }

    // -- the line itself --------------------------------------------------------

    @Test
    fun `a taxon with no family gets no line rather than a wrong one`() {
        assertNull(Families.familyOf(taxonomy, 216))
        assertNull(Families.progressFor(taxonomy, emptyList(), 216, denmark))
    }

    @Test
    fun `a record kept at genus still gets its family's line`() {
        val progress = Families.progressFor(taxonomy, listOf(record("a", 1036775)), 1036775, denmark)!!

        assertEquals("Carabidae", progress.scientificName)
        assertEquals(0, progress.seen, "the genus record is not a species")
    }

    @Test
    fun `the family's common name comes along when it has one`() {
        val progress = Families.progressFor(taxonomy, listOf(record("a", 2498036)), 2498036, denmark)!!

        assertEquals("Ducks, geese and swans", progress.vernacularEn)
    }

    // -- the group screen -------------------------------------------------------

    @Test
    fun `families you have something from come back fullest first`() {
        val records = listOf(record("a", 1036776), record("b", 2498036))

        val families = Families.seenFamilies(taxonomy, records, denmark)

        assertEquals(listOf("Anatidae", "Carabidae"), families.map { it.scientificName })
        assertTrue(families[0].fraction > families[1].fraction)
    }

    @Test
    fun `a family you have nothing from is not a row`() {
        val families = Families.seenFamilies(taxonomy, listOf(record("a", 2498036)), denmark)

        assertEquals(listOf("Anatidae"), families.map { it.scientificName })
    }

    @Test
    fun `finishing a family is something the line can say`() {
        val progress = Families.progressFor(
            taxonomy, listOf(record("a", 2498036)), 2498036, mapOf("Anatidae" to 1)
        )!!

        assertTrue(progress.complete)
        assertEquals(1f, progress.fraction)
    }
}
