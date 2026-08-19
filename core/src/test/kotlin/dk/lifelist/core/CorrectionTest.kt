package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saying "no, it is not that".
 *
 * Reported from real use, twice: a clean photograph of a moth against a plain background, a
 * confident species answer, and the species is simply wrong — a different genus, sometimes a
 * different family. Until now the app had no way to hear it. `refine` narrows, and narrowing is
 * the wrong shape for a correction; there was nothing that could move a record sideways.
 *
 * The rule that matters throughout: **what the model said is never overwritten.** A record that
 * rewrote its own confidence to agree with the correction would make the whole calibration
 * claim unfalsifiable, and calibration is the thing this app is actually selling.
 */
class CorrectionTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta", vernacularEn = "Insects"),
            Taxon(100, 216, "family", "Yponomeutidae", vernacularEn = "Ermine moths"),
            Taxon(110, 100, "genus", "Yponomeuta"),
            Taxon(111, 110, "species", "Yponomeuta evonymella",
                vernacularEn = "Bird-cherry Ermine", leafIndex = 0),
            Taxon(112, 110, "species", "Yponomeuta padella",
                vernacularEn = "Orchard Ermine", leafIndex = 1),
            Taxon(200, 216, "family", "Crambidae", vernacularEn = "Crambid Snout Moths"),
            Taxon(210, 200, "genus", "Crambus"),
            Taxon(211, 210, "species", "Crambus perlellus",
                vernacularEn = "Yellow Satin Veneer", leafIndex = 2),
        )
    )

    private fun record(taxonId: Int, confidence: Float = 0.91f) = Record(
        id = "r", taxonId = taxonId, observedAt = 1_755_000_000_000, photoPaths = listOf("/a.jpg"),
        threshold = 0.70f, modelVersion = "2026-08-18-full", determinedBy = Determiner.MODEL,
        confidence = confidence,
    )

    // -- correcting sideways ----------------------------------------------------

    @Test
    fun `a record can be moved to a taxon in another family entirely`() {
        val corrected = LifeList.correct(taxonomy, record(111), 211, Determiner.USER)

        assertEquals(211, corrected.taxonId)
        assertEquals(Determiner.USER, corrected.determinedBy)
        assertEquals(111, corrected.refinedFrom)
    }

    @Test
    fun `refine still refuses to move sideways, because its callers must not`() {
        // The "settle the species" list only ever offers descendants; if it ever offered
        // something else that would be a bug, and this is the assertion that says so.
        assertFailsWith<IllegalArgumentException> {
            LifeList.refine(taxonomy, record(111), 211, Determiner.USER)
        }
    }

    @Test
    fun `what the model said survives the correction untouched`() {
        val corrected = LifeList.correct(taxonomy, record(111, confidence = 0.94f), 211, Determiner.USER)

        assertEquals(0.94f, corrected.confidence)
        assertEquals("2026-08-18-full", corrected.modelVersion)
        assertEquals(0.70f, corrected.threshold)
    }

    @Test
    fun `the photographs and the date belong to the sighting, not the determination`() {
        val corrected = LifeList.correct(taxonomy, record(111), 211, Determiner.USER)

        assertEquals("r", corrected.id)
        assertEquals(1_755_000_000_000, corrected.observedAt)
        assertEquals(listOf("/a.jpg"), corrected.photoPaths)
    }

    @Test
    fun `correcting twice still remembers what the model originally said`() {
        // Otherwise a second thought erases the evidence that the model was ever wrong.
        val once = LifeList.correct(taxonomy, record(111), 211, Determiner.USER)
        val twice = LifeList.correct(taxonomy, once, 112, Determiner.USER)

        assertEquals(111, twice.refinedFrom)
    }

    @Test
    fun `a correction to nothing, to root, or to itself is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LifeList.correct(taxonomy, record(111), 999_999, Determiner.USER)
        }
        assertFailsWith<IllegalArgumentException> {
            LifeList.correct(taxonomy, record(111), ROOT_ID, Determiner.USER)
        }
        assertFailsWith<IllegalArgumentException> {
            LifeList.correct(taxonomy, record(111), 111, Determiner.USER)
        }
    }

    // -- telling the two apart afterwards ---------------------------------------

    @Test
    fun `a narrowing is recognisable as one`() {
        val settled = LifeList.refine(taxonomy, record(110), 111, Determiner.USER)

        assertTrue(LifeList.wasNarrowed(taxonomy, settled))
    }

    @Test
    fun `a sideways correction is not`() {
        val corrected = LifeList.correct(taxonomy, record(111), 211, Determiner.USER)

        assertFalse(LifeList.wasNarrowed(taxonomy, corrected))
    }

    @Test
    fun `an untouched record was neither`() {
        assertFalse(LifeList.wasNarrowed(taxonomy, record(111)))
    }

    // -- retreating to a rank you actually believe ------------------------------

    @Test
    fun `the ranks above a species are offered deepest first`() {
        // The case with no name to give: an 87% species you do not believe, and no idea what
        // it actually is. You still know it is a moth.
        val up = LifeList.broader(taxonomy, 111).map { it.taxonId }

        assertEquals(listOf(110, 100, 216), up)
    }

    @Test
    fun `root is never offered, because Life is not a determination`() {
        assertTrue(LifeList.broader(taxonomy, 111).none { it.taxonId == ROOT_ID })
    }

    @Test
    fun `a taxon just under root has nowhere left to retreat to`() {
        assertEquals(emptyList(), LifeList.broader(taxonomy, 216))
    }

    @Test
    fun `retreating is a correction, and is recorded as the user's`() {
        val kept = LifeList.correct(taxonomy, record(111), 100, Determiner.USER)

        assertEquals(100, kept.taxonId)
        assertEquals(Determiner.USER, kept.determinedBy)
        assertEquals(111, kept.refinedFrom)
        assertFalse(LifeList.wasNarrowed(taxonomy, kept), "going up is not a narrowing")
    }

    @Test
    fun `what the model claimed survives being disbelieved`() {
        // The point of the whole exercise: a record that quietly dropped the 87% would erase
        // the evidence that the model was overconfident here.
        val kept = LifeList.correct(taxonomy, record(111, confidence = 0.87f), 100, Determiner.USER)

        assertEquals(0.87f, kept.confidence)
        assertEquals(111, kept.refinedFrom)
    }

    // -- finding the taxon you meant --------------------------------------------

    @Test
    fun `an exact common name comes first`() {
        assertEquals(211, LifeList.search(taxonomy, "Yellow Satin Veneer").first().taxonId)
    }

    @Test
    fun `a prefix beats a match buried in the middle`() {
        val hits = LifeList.search(taxonomy, "Crambus").map { it.taxonId }

        assertEquals(210, hits.first(), "the genus itself starts with it")
    }

    @Test
    fun `the scientific name works when that is what you have`() {
        assertEquals(112, LifeList.search(taxonomy, "Yponomeuta padella").first().taxonId)
    }

    @Test
    fun `at equal match quality a species comes before the group above it`() {
        // "rambus" is buried in the middle of both *Crambus* and *Crambus perlellus*, so the
        // two score identically and the tie-break decides. Someone searching by name almost
        // always wants the species; offering the genus first is an extra tap every time.
        val hits = LifeList.search(taxonomy, "rambus").map { it.taxonId }

        assertTrue(hits.indexOf(211) < hits.indexOf(210), "species before its genus")
    }

    @Test
    fun `ties break by rank then alphabetically, so a query always gives the same list`() {
        // "ponomeuta" sits in the middle of all three names, so all three score the same and
        // both tie-breaks have to do the work: species first, then by name.
        val hits = LifeList.search(taxonomy, "ponomeuta").map { it.taxonId }

        assertEquals(listOf(111, 112, 110), hits)
    }

    @Test
    fun `higher ranks are findable too, because sometimes that is the honest answer`() {
        assertTrue(LifeList.search(taxonomy, "Crambid").any { it.taxonId == 200 })
    }

    @Test
    fun `root is never offered`() {
        assertTrue(LifeList.search(taxonomy, "life").none { it.taxonId == ROOT_ID })
    }

    @Test
    fun `one letter is not a search`() {
        // 4,657 nodes and a single character is every scroll in the world.
        assertTrue(LifeList.search(taxonomy, "y").isEmpty())
    }

    @Test
    fun `the limit is honoured`() {
        assertEquals(1, LifeList.search(taxonomy, "moth", limit = 1).size)
        assertTrue(LifeList.search(taxonomy, "moth", limit = 50).size > 1)
    }
}
