package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling "I knew what it was" apart from "the model said, and I disagreed".
 *
 * Both are `USER`, which is why this needs a test rather than a glance: the record page prints
 * a confidence, a threshold and a model version, and on a record no model ever touched all
 * three would be invented.
 */
class UnaidedTest {

    private fun record(
        by: Determiner,
        confidence: Float? = null,
        refinedFrom: Int? = null,
    ) = Record(
        id = "r", taxonId = 1688020, observedAt = 0L, photoPaths = emptyList(),
        threshold = 0f, modelVersion = "2026-08-18-full", determinedBy = by,
        confidence = confidence, refinedFrom = refinedFrom,
    )

    @Test
    fun `a species you simply knew`() {
        assertTrue(record(Determiner.USER).unaided)
    }

    @Test
    fun `anything the model determined is not unaided`() {
        assertFalse(record(Determiner.MODEL, confidence = 0.91f).unaided)
        // Even with nothing recorded for the confidence: the model still made the call.
        assertFalse(record(Determiner.MODEL).unaided)
    }

    @Test
    fun `a correction is not unaided — the model saw that one and you disagreed`() {
        assertFalse(record(Determiner.USER, confidence = 0.62f).unaided)
        assertFalse(record(Determiner.USER, refinedFrom = 600).unaided)
        assertFalse(record(Determiner.USER, confidence = 0.62f, refinedFrom = 600).unaided)
    }

    @Test
    fun `an unaided record is still a record, and still a species`() {
        // It counts. A fox you watched cross a field is a sighting, and a list that quietly
        // filed it under "not really" would be a worse list. Nothing in the counting looks at
        // who determined it, and this test is here so that stays true.
        val taxonomy = Taxonomy(
            listOf(
                Taxon(0, null, "root", "Life"),
                Taxon(212, 0, "class", "Aves"),
                Taxon(2986, 212, "family", "Anatidae"),
                Taxon(2498036, 2986, "species", "Anas platyrhynchos", leafIndex = 0),
            )
        )
        val mine = record(Determiner.USER).copy(taxonId = 2498036)
        val totals = LifeList.totals(taxonomy, listOf(mine))
        assertTrue(mine.unaided)
        assertTrue(totals.records == 1, "counted ${totals.records} records")
        assertTrue(totals.toSpecies == 1, "counted ${totals.toSpecies} to species")
    }
}
