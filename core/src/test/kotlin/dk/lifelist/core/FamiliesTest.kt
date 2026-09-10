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

        // Two, not three: `Carabus sp.` is a class, not a species. This line used to read
        // `assertEquals(3, ..., "three Carabidae leaves, including Carabus sp.")`, which wrote
        // the bug down as the intention rather than catching it (§68).
        assertEquals(2, progress.total, "two findable Carabidae; Carabus sp. is not one")
        assertEquals(Families.Source.APP, progress.source)
    }

    @Test
    fun `a Danish total smaller than our own vocabulary does not shrink the denominator`() {
        // The Red List lists 19 Plantaginaceae where the model recognises 31. A denominator
        // below the numerator is worse than one that undersells the country.
        val progress = Families.progressFor(
            taxonomy, listOf(record("a", 1036776)), 1036776, mapOf("Carabidae" to 1)
        )!!

        assertEquals(2, progress.total)
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

    // -- what is behind the number ----------------------------------------------

    @Test
    fun `opening a family lists every species it can name, found ones first`() {
        val records = listOf(record("a", 1036777))  // Carabus nemoralis

        val members = Families.membersOf(taxonomy, records, 5602)

        assertEquals(
            listOf("Carabus nemoralis", "Carabus granulatus"),
            members.map { it.scientificName },
        )
        assertEquals(listOf(true, false), members.map { it.seen })
    }

    @Test
    fun `the synthetic sp leaf is not a species anybody can go and find`() {
        // `Carabus sp.` is a class the head can be trained on (§1.1a), not a bush-cricket
        // waiting in a hedge. It is excluded here exactly as it is from the count.
        val members = Families.membersOf(taxonomy, emptyList(), 5602)

        assertTrue(members.none { it.taxonId < 0 }, "found ${members.map { it.taxonId }}")
        assertEquals(2, members.size)
    }

    @Test
    fun `a record kept at genus does not tick off a species`() {
        // Consistent with `seenIn`: honest record, real sighting, but not one of the two.
        val members = Families.membersOf(taxonomy, listOf(record("a", 1036775)), 5602)

        assertTrue(members.none { it.seen })
    }

    @Test
    fun `a Danish total the app cannot name is reported rather than quietly dropped`() {
        // "1 of 65 Anatidae in Denmark" can name one duck. Listing one and calling it 65 —
        // or listing one and saying nothing — are both worse than saying how many are missing.
        val progress = Families.progressFor(taxonomy, listOf(record("a", 2498036)), 2498036, denmark)!!
        val members = Families.membersOf(taxonomy, listOf(record("a", 2498036)), 2986)

        assertEquals(65, progress.total)
        assertEquals(1, members.size)
        assertEquals(64, Families.unnamed(progress, members))
    }

    @Test
    fun `a family the app knows completely has nothing unnamed`() {
        val progress = Families.progressFor(taxonomy, listOf(record("a", 1036776)), 1036776, emptyMap())!!
        val members = Families.membersOf(taxonomy, emptyList(), 5602)

        assertEquals(0, Families.unnamed(progress, members))
    }

    @Test
    fun `a synthetic sp leaf is not a species the denominator may count`() {
        // Carabidae here is `Carabus sp.`, granulatus and nemoralis. `seenIn` has always
        // excluded the first, so counting it made "1 of 3" a fraction with two different kinds
        // of thing in it — and one third of the family impossible to ever find (§68).
        assertEquals(2, Families.knownToApp(taxonomy, 5602))
    }

    @Test
    fun `the denominator and the roster behind it are the same length`() {
        // The property that was broken: whatever the number says, opening the row must be able
        // to show that many species.
        val progress = Families.progressFor(taxonomy, listOf(record("a", 1036776)), 1036776)!!

        assertEquals(progress.total, Families.membersOf(taxonomy, emptyList(), 5602).size)
    }
}
