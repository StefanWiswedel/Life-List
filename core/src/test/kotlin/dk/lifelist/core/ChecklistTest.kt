package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind "4 of 74 ducks".
 *
 * Small and dull, and the place a denominator quietly becomes a claim about the wrong world.
 */
class ChecklistTest {

    private fun family(id: Int, name: String, species: Int, model: Int, lineage: List<Int>) =
        ChecklistFamily(id, name, null, lineage, species, model)

    private fun species(id: Int, family: Int, records: Int, model: Boolean = true) =
        ChecklistSpecies(id, "Species $id", null, family, records, model)

    private val aves = listOf(1, 44, 212)
    private val insecta = listOf(1, 54, 216)

    private val checklist = Checklist(
        families = listOf(
            family(2986, "Anatidae", species = 74, model = 41, lineage = aves + 2986),
            family(9316, "Laridae", species = 38, model = 16, lineage = aves + 9316),
            family(5602, "Carabidae", species = 330, model = 44, lineage = insecta + 5602),
        ).associateBy { it.taxonId },
        species = listOf(
            species(1, 2986, records = 779_131),
            species(2, 2986, records = 400_000),
            species(3, 2986, records = 2_814, model = false),
            species(4, 9316, records = 100_000),
            species(5, 5602, records = 900),
        ).associateBy { it.taxonId },
    )

    private fun record(taxonId: Int) = Record(
        id = "r$taxonId", taxonId = taxonId, observedAt = 0L, photoPaths = emptyList(),
        threshold = 0.7f, modelVersion = "t", determinedBy = Determiner.MODEL,
    )

    // -- grouping ---------------------------------------------------------------

    @Test
    fun `a family's group comes from its own lineage`() {
        assertEquals("Birds", checklist.groupOf(checklist.families.getValue(2986)))
        assertEquals("Insects", checklist.groupOf(checklist.families.getValue(5602)))
    }

    @Test
    fun `a family in no group at all lands in Other rather than nowhere`() {
        val orphan = family(1, "Nowhere", 3, 0, listOf(1, 99999))
        assertEquals(UNGROUPED, checklist.groupOf(orphan))
    }

    // -- the denominator --------------------------------------------------------

    @Test
    fun `the total is Denmark's, not the number of rows we happen to hold`() {
        // The whole point. This checklist holds three duck species; Denmark has seventy-four,
        // and the family says so. A screen that counted its own rows would report 2 of 3.
        val lines = Index.families(checklist, "Birds", listOf(record(1), record(2)))
        val ducks = lines.first { it.family.taxonId == 2986 }

        assertEquals(2, ducks.found)
        assertEquals(74, ducks.total)
    }

    @Test
    fun `species the camera cannot name are counted in the total, not subtracted from it`() {
        val ducks = Index.families(checklist, "Birds", emptyList())
            .first { it.family.taxonId == 2986 }

        assertEquals(74, ducks.total)
        assertEquals(74 - 41, ducks.byHandOnly)
    }

    @Test
    fun `a record kept at genus is not one of the seventy-four`() {
        // It is an honest record and it is not a species tick (§19). Counting it would make
        // the one number on the screen mean two different things.
        val genus = record(-2986)
        val lines = Index.families(checklist, "Birds", listOf(genus))

        assertEquals(0, lines.first { it.family.taxonId == 2986 }.found)
    }

    @Test
    fun `a family with nothing found is still a line`() {
        // The unfound families are the half of this screen worth reading.
        val lines = Index.families(checklist, "Birds", emptyList())
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.found == 0 })
    }

    // -- order ------------------------------------------------------------------

    @Test
    fun `families come fullest first`() {
        // Not alphabetically, and deliberately not "the ones you have started" — an order that
        // changes as you play makes the page you learned unlearnable.
        val lines = Index.families(checklist, "Birds", listOf(record(4)))
        assertEquals(listOf(2986, 9316), lines.map { it.family.taxonId })
    }

    @Test
    fun `a family's species come yours first, then commonest`() {
        val members = Index.members(checklist, 2986, listOf(record(3)))

        // The one found is at the top even though it is the *least* recorded of the three.
        assertEquals(listOf(3, 1, 2), members.map { it.species.taxonId })
        assertTrue(members.first().seen)
    }

    @Test
    fun `commonest first, because a roster is a to-do list`() {
        // Alphabetical would open the ducks on a vagrant nobody has seen since 1987.
        val members = Index.members(checklist, 2986, emptyList())
        assertEquals(listOf(1, 2, 3), members.map { it.species.taxonId })
    }

    // -- groups -----------------------------------------------------------------

    @Test
    fun `a group's standing sums its families, not its rows`() {
        val groups = Index.groups(checklist, listOf(record(1), record(4)))
        val birds = groups.first { it.label == "Birds" }

        assertEquals(2, birds.found)
        assertEquals(74 + 38, birds.total)
        assertEquals(2, birds.families)
    }

    @Test
    fun `a group Denmark has nothing in is not offered`() {
        // Empty *groups* are worth keeping on a life list — "no amphibians yet" sends somebody
        // looking. A group with no species in the country at all is not a gap anybody can fill.
        val labels = Index.groups(checklist, emptyList()).map { it.label }
        assertEquals(listOf("Birds", "Insects"), labels)
    }

    @Test
    fun `an empty checklist says nothing rather than dividing by zero`() {
        assertEquals(emptyList(), Index.groups(Checklist.EMPTY, listOf(record(1))))
        assertEquals(emptyList(), Index.families(Checklist.EMPTY, "Birds", emptyList()))
        assertEquals(emptyList(), Index.members(Checklist.EMPTY, 2986, emptyList()))
    }

    @Test
    fun `a family with no species recorded has a zero fraction, not a crash`() {
        val empty = Checklist(
            families = mapOf(1 to family(1, "Nothing", species = 0, model = 0, lineage = aves + 1)),
            species = emptyMap(),
        )
        val line = Index.families(empty, "Birds", emptyList()).single()
        assertEquals(0f, line.fraction)
        assertTrue(!line.complete)
    }
}
