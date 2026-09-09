package dk.lifelist.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behaviour the golden fixture pins down, stated as claims rather than as numbers.
 *
 * The fixture proves Kotlin matches Python. These say what both of them are supposed to do, so
 * a change that moves both in the same wrong direction still fails something.
 */
class AudioTest {

    // 0 chiffchaff, 1 willow warbler, 2 robin, 3 common frog
    private val chiffchaff = 100
    private val willow = 101
    private val robin = 110
    private val frog = 200

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(1, 0, "kingdom", "Animalia"),
            Taxon(2, 1, "phylum", "Chordata"),
            Taxon(3, 2, "class", "Aves"),
            Taxon(4, 3, "order", "Passeriformes"),
            Taxon(5, 4, "family", "Phylloscopidae"),
            Taxon(6, 5, "genus", "Phylloscopus"),
            Taxon(chiffchaff, 6, "species", "Phylloscopus collybita", leafIndex = 0),
            Taxon(willow, 6, "species", "Phylloscopus trochilus", leafIndex = 1),
            Taxon(7, 4, "family", "Muscicapidae"),
            Taxon(8, 7, "genus", "Erithacus"),
            Taxon(robin, 8, "species", "Erithacus rubecula", leafIndex = 2),
            // amphibians — the vision model's blind spot, and why audio earns its place
            Taxon(9, 2, "class", "Amphibia"),
            Taxon(10, 9, "order", "Anura"),
            Taxon(11, 10, "family", "Ranidae"),
            Taxon(12, 11, "genus", "Rana"),
            Taxon(frog, 12, "species", "Rana temporaria", leafIndex = 3),
        )
    )

    // -- detection is multi-label ------------------------------------------------

    @Test
    fun `three singers give three detections`() {
        val found = Audio.detect(mapOf(chiffchaff to 0.8f, robin to 0.6f, frog to 0.3f))

        assertEquals(listOf(chiffchaff, robin, frog), found.map { it.taxonId })
    }

    @Test
    fun `sigmoid scores need not sum to one`() {
        val found = Audio.detect(mapOf(chiffchaff to 0.9f, robin to 0.9f, frog to 0.9f))

        assertEquals(3, found.size)
    }

    // -- confusion sets are taxonomically coherent -------------------------------

    @Test
    fun `a frog does not compete with a warbler`() {
        val detection = Audio.Detection(chiffchaff, 0.8f, 0f)

        val set = Audio.confusionSet(taxonomy, mapOf(chiffchaff to 0.8f, frog to 0.7f), detection)

        assertEquals(listOf(chiffchaff), set)
    }

    @Test
    fun `two warblers a poor recording cannot separate resolve to the genus`() {
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.45f, willow to 0.40f, robin to 0.05f),
            threshold = 0.70f,
        )

        assertEquals("genus", found.first().result.rank)
        assertEquals(6, found.first().result.taxonId)
    }

    // -- the prior is a prior, not a mask ----------------------------------------

    @Test
    fun `a hostile prior outvotes a candidate without removing it`() {
        // Built prior-first, the willow warbler at 0.95 x 1e-6 would fall below the 0.485
        // margin and leave the confusion set entirely — a silent mask wearing a prior's
        // clothes, and the record a naturalist most wants to keep. Built to spec, it is
        // outvoted and visible.
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.97f, willow to 0.95f),
            threshold = 0.70f,
            geo = mapOf(chiffchaff to 0.99f, willow to 0.000001f),
        )

        val first = found.first()
        assertEquals(listOf(chiffchaff, willow), first.confusionSet)
        assertEquals(chiffchaff, first.result.taxonId)
        assertEquals(0.95f, first.rawScores.getValue(willow))
    }

    @Test
    fun `a zero range likelihood lowers the odds but never erases the species`() {
        val weighted = Audio.applyGeoPrior(mapOf(willow to 0.55f), mapOf(willow to 0f))

        assertTrue(weighted.getValue(willow) > 0f, "clamped to 1e-6, never to zero")
        assertTrue(weighted.getValue(willow) < 0.55f)
    }

    @Test
    fun `weight zero disables the prior`() {
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.45f, willow to 0.40f),
            threshold = 0.70f,
            geo = mapOf(chiffchaff to 0.01f, willow to 0.99f),
            geoWeight = 0f,
        )

        assertFalse(found.first().geoApplied)
        assertEquals(6, found.first().result.taxonId)
    }

    // -- the raw scores stay recoverable -----------------------------------------

    @Test
    fun `the pre-prior scores are kept for every member of the set`() {
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.45f, willow to 0.40f, robin to 0.05f),
            threshold = 0.70f,
            geo = mapOf(chiffchaff to 0.05f, willow to 0.95f),
        )

        assertEquals(
            mapOf(chiffchaff to 0.45f, willow to 0.40f),
            found.first().rawScores,
        )
    }

    // -- "none of these" (spec §4A.3) --------------------------------------------

    @Test
    fun `a lone detection is only as sure as BirdNET was`() {
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.99f, robin to 0.01f),
            threshold = 0.70f,
        )

        assertEquals(chiffchaff, found.first().result.taxonId)
        assertTrue(abs(0.99f - found.first().result.probability) < 1e-4f)
    }

    @Test
    fun `a weak lone detection is refused rather than named`() {
        // Renormalising across the confusion set alone divided a lone detection by itself and
        // returned 100%, so 0.26 and 0.99 produced identical cards and no threshold could
        // refuse either (VERIFICATION §60). The absent outcome carries the rest.
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.26f, robin to 0.01f),
            threshold = 0.70f,
        )

        assertTrue(found.first().result.isUnidentified)
        assertTrue(abs(0.74f - found.first().absent) < 1e-4f)
    }

    @Test
    fun `two strong congeners still reach the genus`() {
        // The absent outcome must not put the genus answer out of reach: two confident
        // candidates leave almost nothing for "none of these", so the pair clears the
        // threshold together even though neither clears it alone.
        val found = Audio.identifyWindow(
            taxonomy,
            mapOf(chiffchaff to 0.90f, willow to 0.85f),
            threshold = 0.70f,
        )

        assertEquals("genus", found.first().result.rank)
        assertTrue(found.first().absent < 0.02f)
    }
}
