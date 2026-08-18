package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The display layer is where an honest hedge quietly becomes a confident species name, so it
 * is tested rather than eyeballed in an emulator.
 *
 * A small hand-built taxonomy: two ducks in *Anas*, one swan in *Cygnus*, both in Anatidae.
 */
class PresentationTest {

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(1, 0, "family", "Anatidae", vernacularEn = "Ducks, geese and swans"),
            Taxon(2, 1, "genus", "Anas"),
            Taxon(3, 1, "genus", "Cygnus"),
            Taxon(10, 2, "species", "Anas platyrhynchos", vernacularEn = "Mallard",
                vernacularDa = "Gråand", leafIndex = 0),
            Taxon(11, 2, "species", "Anas crecca", vernacularEn = "Eurasian Teal", leafIndex = 1),
            Taxon(12, 3, "species", "Cygnus olor", vernacularEn = "Mute Swan", leafIndex = 2),
        )
    )

    private fun result(
        taxonId: Int,
        rank: String,
        probability: Float,
        threshold: Float = 0.70f,
    ) = RollupResult(
        taxonId = taxonId,
        rank = rank,
        probability = probability,
        candidates = listOf(
            Candidate(10, 0, 0.40f),
            Candidate(11, 1, 0.38f),
            Candidate(12, 2, 0.22f),
        ),
        threshold = threshold,
    )

    // -- nomenclature is not styling --------------------------------------------

    @Test
    fun `a binomial is italic`() {
        assertEquals(
            listOf(NameRun("Anas platyrhynchos", italic = true)),
            Presentation.styleName("Anas platyrhynchos", "species"),
        )
    }

    @Test
    fun `a family is not italic`() {
        assertEquals(
            listOf(NameRun("Anatidae", italic = false)),
            Presentation.styleName("Anatidae", "family"),
        )
    }

    @Test
    fun `agg stays roman inside an italic name`() {
        // shared/taxonomy-spec.md §1.2: "never italicised in full; the agg. stays roman".
        assertEquals(
            listOf(
                NameRun("Taraxacum officinale", italic = true),
                NameRun("agg.", italic = false),
            ),
            Presentation.styleName("Taraxacum officinale agg.", "species_aggregate"),
        )
    }

    @Test
    fun `a subspecies trinomial is italic throughout`() {
        assertEquals(
            listOf(NameRun("Anas platyrhynchos domesticus", italic = true)),
            Presentation.styleName("Anas platyrhynchos domesticus", "subspecies"),
        )
    }

    @Test
    fun `plain text round trips a styled name`() {
        val runs = Presentation.styleName("Taraxacum officinale agg.", "species_aggregate")
        assertEquals("Taraxacum officinale agg.", Presentation.plain(runs))
    }

    // -- confidence -------------------------------------------------------------

    @Test
    fun `confidence renders whole percent with no decimals`() {
        assertEquals("71%", Presentation.confidence(0.714f).percent)
        assertEquals("40%", Presentation.confidence(0.40f).percent)
    }

    @Test
    fun `the bar never overflows`() {
        assertEquals(1.0f, Presentation.confidence(1.4f).barFraction)
        assertEquals(0.0f, Presentation.confidence(-0.1f).barFraction)
    }

    // -- the three answers ------------------------------------------------------

    @Test
    fun `a species answer names the species and does not label the rank`() {
        val answer = Presentation.present(taxonomy, result(10, "species", 0.85f))

        assertEquals(AnswerKind.LEAF, answer.kind)
        assertEquals("Anas platyrhynchos", Presentation.plain(answer.scientificName))
        assertEquals("Mallard", answer.vernacular)
        assertNull(answer.rankLabel, "rank is not the point when the answer is a species")
    }

    @Test
    fun `a genus answer says so, and says why, in the user's own threshold`() {
        val answer = Presentation.present(taxonomy, result(2, "genus", 0.78f, threshold = 0.70f))

        assertEquals(AnswerKind.HIGHER_RANK, answer.kind)
        assertEquals("genus", answer.rankLabel)
        assertEquals("Anas", Presentation.plain(answer.scientificName))
        assertTrue(answer.explanation.contains("genus"))
        assertTrue(
            answer.explanation.contains("70%"),
            "the hedge must cite the threshold actually in force, not a constant",
        )
    }

    @Test
    fun `the explanation follows a changed threshold`() {
        val strict = Presentation.present(taxonomy, result(2, "genus", 0.78f, threshold = 0.95f))

        assertTrue(strict.explanation.contains("95%"))
    }

    @Test
    fun `an unidentified result names nothing at all`() {
        val answer = Presentation.present(taxonomy, result(0, "root", 1.0f))

        assertEquals(AnswerKind.UNIDENTIFIED, answer.kind)
        assertTrue(answer.scientificName.isEmpty(), "root must never render as 'Life'")
        assertNull(answer.vernacular)
        assertFalse(answer.explanation.contains("Life"))
    }

    // -- candidates: shown, not hidden ------------------------------------------

    @Test
    fun `candidates outside the answer are kept and marked`() {
        // §4.3: the runner-up genus is exactly what a naturalist wants to see.
        val answer = Presentation.present(taxonomy, result(2, "genus", 0.78f))

        assertEquals(3, answer.candidates.size)
        val swan = answer.candidates.single { it.taxonId == 12 }
        assertFalse(swan.withinAnswer)
        assertTrue(answer.candidates.single { it.taxonId == 10 }.withinAnswer)
    }

    @Test
    fun `candidate names carry their own vernacular and styling`() {
        val answer = Presentation.present(taxonomy, result(2, "genus", 0.78f))
        val teal = answer.candidates.single { it.taxonId == 11 }

        assertEquals("Anas crecca", Presentation.plain(teal.name))
        assertEquals("Eurasian Teal", teal.vernacular)
        assertTrue(teal.name.all { it.italic })
    }

    // -- lineage as a printed key -----------------------------------------------

    @Test
    fun `lineage runs root first and marks the answer`() {
        val answer = Presentation.present(taxonomy, result(2, "genus", 0.78f))

        assertEquals(listOf(0, 1, 2), answer.lineage.map { it.taxonId })
        assertEquals(listOf("root", "family", "genus"), answer.lineage.map { it.rank })
        assertEquals(2, answer.lineage.single { it.isAnswer }.taxonId)
    }

    @Test
    fun `lineage styles each step by its own rank`() {
        val answer = Presentation.present(taxonomy, result(10, "species", 0.85f))
        val byRank = answer.lineage.associateBy { it.rank }

        assertFalse(byRank.getValue("family").name.single().italic)
        assertTrue(byRank.getValue("genus").name.single().italic)
        assertTrue(byRank.getValue("species").name.single().italic)
    }

    // -- Danish is stored, not surfaced (CLAUDE.md, decided) --------------------

    @Test
    fun `Danish vernaculars are never shown`() {
        val answer = Presentation.present(taxonomy, result(10, "species", 0.85f))

        assertNotNull(taxonomy.node(10).vernacularDa)
        assertEquals("Mallard", answer.vernacular)
        assertFalse(answer.explanation.contains("Gråand"))
    }
}
