package dk.lifelist.core

/**
 * Everything Denmark has, against everything you have found.
 *
 * The app's taxonomy is its *output space* — the species a model was trained on — and using it
 * as the denominator was fine while the app only answered questions. The moment a screen offers
 * to show what you have **not** found, the missing species have to be missing from Denmark's
 * list rather than from ours, or the app quietly reports a weevil family complete at 64 of 490
 * (VERIFICATION §81).
 *
 * So this is a second, larger tree that the model knows nothing about: 26,722 species in 2,461
 * families, from GBIF's Danish occurrence records. Most of it the camera cannot identify, and
 * that is not a defect to hide — it is the difference between a list of what a model can do and
 * a list of what is out there.
 */
data class ChecklistSpecies(
    val taxonId: Int,
    val scientificName: String,
    val vernacularEn: String?,
    val familyId: Int?,
    /** Danish occurrence records. Recording effort, not abundance (§72). */
    val records: Int,
    /** Whether the bundled model can identify this one, or it can only be ticked by hand. */
    val identifiable: Boolean,
) {
    val name: String get() = vernacularEn ?: scientificName
}

data class ChecklistFamily(
    val taxonId: Int,
    val scientificName: String,
    val vernacularEn: String?,
    /** Ancestor keys, coarsest first. The group is read off this with [LifeList.DEFAULT_GROUPS]. */
    val lineage: List<Int>,
    /** Species of this family recorded in Denmark. */
    val species: Int,
    /** How many of those the model can identify. */
    val identifiable: Int,
) {
    val name: String get() = vernacularEn ?: scientificName
}

class Checklist(
    val families: Map<Int, ChecklistFamily>,
    val species: Map<Int, ChecklistSpecies>,
) {
    /** Species of a family, unordered. Empty for a family this checklist has never heard of. */
    private val byFamily: Map<Int, List<ChecklistSpecies>> =
        species.values.filter { it.familyId != null }.groupBy { it.familyId!! }

    fun membersOf(familyId: Int): List<ChecklistSpecies> = byFamily[familyId].orEmpty()

    /**
     * The group a family belongs to, from its own lineage.
     *
     * Read at the family rather than the species, so a species' group is a fact about its
     * family and cannot disagree with its neighbours'.
     */
    fun groupOf(family: ChecklistFamily, groups: List<Group> = DEFAULT_GROUPS): String =
        groups.firstOrNull { it.taxonId in family.lineage }?.label ?: UNGROUPED

    fun familiesIn(group: String, groups: List<Group> = DEFAULT_GROUPS): List<ChecklistFamily> =
        families.values.filter { groupOf(it, groups) == group }

    /** A name for a taxon this checklist knows and the model does not. */
    fun nameOf(taxonId: Int): String? = species[taxonId]?.name ?: families[taxonId]?.name

    companion object {
        val EMPTY = Checklist(emptyMap(), emptyMap())
    }
}

/**
 * The index: what you have found, laid over what there is.
 *
 * Pure, so the arithmetic that decides "3 of 74" can be tested without an asset or a phone.
 */
object Index {

    data class FamilyLine(
        val family: ChecklistFamily,
        /** Distinct species of this family on your list. */
        val found: Int,
    ) {
        val total: Int get() = family.species
        val fraction: Float get() = if (total <= 0) 0f else found.toFloat() / total
        val complete: Boolean get() = total > 0 && found >= total

        /** Species here the camera cannot name, so the only way on to the list is by hand. */
        val byHandOnly: Int get() = (total - family.identifiable).coerceAtLeast(0)
    }

    data class Member(val species: ChecklistSpecies, val seen: Boolean)

    /**
     * Which species are on the list, as taxon ids.
     *
     * A record kept at genus or family is deliberately **not** counted here. "How many of the
     * 74 Danish ducks have you found" is a question about species, and a record that says
     * *Anas* is an honest record and not one of the seventy-four (§19).
     */
    fun found(records: List<Record>): Set<Int> = records.map { it.taxonId }.toSet()

    /**
     * A group's families, fullest first.
     *
     * Size rather than alphabet, and deliberately not "the ones you have started" — an order
     * that changes as you play makes the page you learned unlearnable. Ducks, waders and gulls
     * at the top of the birds is a better first screen than Aegithalidae.
     */
    fun families(
        checklist: Checklist,
        group: String,
        records: List<Record>,
        groups: List<Group> = DEFAULT_GROUPS,
    ): List<FamilyLine> {
        val seen = found(records)
        return checklist.familiesIn(group, groups)
            .map { family ->
                FamilyLine(
                    family = family,
                    found = checklist.membersOf(family.taxonId).count { it.taxonId in seen },
                )
            }
            .sortedWith(compareByDescending<FamilyLine> { it.total }.thenBy { it.family.name })
    }

    /**
     * A family's species: the ones you have first, then the rest commonest first.
     *
     * Commonest, not alphabetical. The roster is a to-do list, and the useful order for a
     * to-do list of species is the one that puts what you might actually meet at the top —
     * which is what the Danish record count is for. Alphabetical would open the ducks on a
     * vagrant nobody has seen since 1987.
     */
    fun members(
        checklist: Checklist,
        familyId: Int,
        records: List<Record>,
    ): List<Member> {
        val seen = found(records)
        return checklist.membersOf(familyId)
            .map { Member(it, it.taxonId in seen) }
            .sortedWith(
                compareByDescending<Member> { it.seen }
                    .thenByDescending { it.species.records }
                    .thenBy { it.species.scientificName }
            )
    }

    /** One group's standing: species found, species Denmark has. */
    data class GroupLine(val label: String, val found: Int, val total: Int, val families: Int) {
        val fraction: Float get() = if (total <= 0) 0f else found.toFloat() / total
    }

    fun groups(
        checklist: Checklist,
        records: List<Record>,
        groups: List<Group> = DEFAULT_GROUPS,
    ): List<GroupLine> {
        val seen = found(records)
        val labels = (groups.map { it.label } + UNGROUPED).distinct()
        val byGroup = checklist.families.values.groupBy { checklist.groupOf(it, groups) }
        return labels.map { label ->
            val families = byGroup[label].orEmpty()
            GroupLine(
                label = label,
                found = families.sumOf { family ->
                    checklist.membersOf(family.taxonId).count { it.taxonId in seen }
                },
                total = families.sumOf { it.species },
                families = families.size,
            )
        }.filter { it.total > 0 }
    }
}
