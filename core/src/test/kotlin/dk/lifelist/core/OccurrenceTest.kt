package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saying how often something is recorded without implying how many there are.
 */
class OccurrenceTest {

    private fun occurrence(records: Int, rank: Int, of: Int) = Occurrence(1, records, rank, of)

    @Test
    fun `the least recorded of a family is called that`() {
        val phrase = Occurrences.phrase(occurrence(115, 40, 40), "Carabidae")

        assertTrue("least recorded" in phrase, phrase)
        assertTrue("115" in phrase, phrase)
    }

    @Test
    fun `the most recorded of a family is called that`() {
        assertEquals(Occurrence.Band.WIDELY, occurrence(686_000, 1, 40).band)
    }

    @Test
    fun `the middle of a family gets the number and no adjective`() {
        // Most species are unremarkable by this measure, and an adjective on every one of them
        // is noise that teaches the reader to skip the line.
        val phrase = Occurrences.phrase(occurrence(900, 20, 40), "Carabidae")

        assertEquals("900 Danish records", phrase)
    }

    @Test
    fun `no records at all is the whole fact, without a comparison`() {
        // Ranking a zero against other zeroes is arithmetic pretending to be information.
        assertEquals("No Danish records at all", Occurrences.phrase(occurrence(0, 1, 40), "Ranidae"))
    }

    @Test
    fun `a family of one has no position worth reporting`() {
        // "Fewest of its family" is silly when its family is itself. Hornwrack is exactly this.
        val phrase = Occurrences.phrase(occurrence(115, 1, 1), "Flustridae")

        assertEquals("115 Danish records", phrase)
        assertEquals(Occurrence.Band.UNPLACED, occurrence(115, 1, 1).band)
    }

    @Test
    fun `only the scarce and the absent are worth drawing attention to`() {
        assertTrue(Occurrences.notable(occurrence(0, 1, 40)))
        assertTrue(Occurrences.notable(occurrence(115, 40, 40)))
        assertFalse(Occurrences.notable(occurrence(900, 20, 40)))
        assertFalse(Occurrences.notable(occurrence(686_000, 1, 40)))
    }

    @Test
    fun `the sentence never says rare`() {
        // "Rare" is a claim about the animal; this is a fact about the records. The distinction
        // is the whole reason the figure is allowed on the screen at all.
        val phrases = listOf(
            Occurrences.phrase(occurrence(0, 1, 9), "Ranidae"),
            Occurrences.phrase(occurrence(3, 9, 9), "Ranidae"),
            Occurrences.phrase(occurrence(900, 5, 9), "Ranidae"),
            Occurrences.phrase(occurrence(90_000, 1, 9), "Ranidae"),
        )

        for (phrase in phrases) {
            assertFalse("rare" in phrase.lowercase(), phrase)
            assertTrue("record" in phrase.lowercase(), phrase)
        }
    }

    @Test
    fun `big numbers are grouped so they can be read`() {
        assertEquals("686,000", Occurrences.grouped(686_000))
        assertEquals("900", Occurrences.grouped(900))
        assertEquals("1,234,567", Occurrences.grouped(1_234_567))
    }

    @Test
    fun `the fraction runs from the most recorded to the least`() {
        assertEquals(0f, occurrence(1, 1, 41).fraction)
        assertEquals(1f, occurrence(1, 41, 41).fraction)
        assertEquals(0.5f, occurrence(1, 21, 41).fraction)
    }
}
