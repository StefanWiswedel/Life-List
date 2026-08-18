package dk.lifelist.core

/**
 * The life list: what has been identified, kept at whatever rank the evidence supported.
 *
 * This is the product's actual differentiator, per VERIFICATION.md §19. Seek will tell you
 * something is a katydid and then refuse to save it, because its record has a species field
 * and a katydid is not a species. A record here holds the node the rollup returned, whatever
 * rank that is, and the list counts honestly rather than flattering.
 *
 * Pure Kotlin over a taxonomy and a list of records: no storage, no Android, no clock. All
 * of it testable, which matters because the counting rules are where a life list quietly
 * turns into a leaderboard.
 */

/** Who decided. A tap is not a model prediction and must not be reported as one (§20). */
enum class Determiner { MODEL, USER }

data class Record(
    val id: String,
    /** The node returned — a species, a genus, `Carabus sp.`, anything but root. */
    val taxonId: Int,
    /** Epoch millis. Passed in rather than read, so tests are not at the mercy of a clock. */
    val observedAt: Long,
    val photoPath: String?,
    /** The threshold in force when this was determined, so §4.4 can re-render it honestly. */
    val threshold: Float,
    val modelVersion: String,
    val determinedBy: Determiner,
    /** Set when a record was later refined; the original determination is never overwritten. */
    val refinedFrom: Int? = null,
)

/** The groups the list is broken into — BUILD.md §4.2, and what Seek gets right. */
data class Group(val label: String, val taxonId: Int)

val DEFAULT_GROUPS: List<Group> = listOf(
    // Order matters: the first match wins, so Insecta is tested before Animalia would be.
    Group("Birds", 212),
    Group("Mammals", 359),
    Group("Reptiles", 358),
    Group("Amphibians", 131),
    Group("Fish", 204),
    Group("Insects", 216),
    Group("Arachnids", 367),
    Group("Molluscs", 52),
    Group("Plants", 6),
    Group("Fungi", 5),
)

const val UNGROUPED = "Other"

data class GroupTally(
    val label: String,
    val records: List<Record>,
) {
    /** Records determined all the way to a leaf of the taxonomy. */
    fun toSpecies(taxonomy: Taxonomy): Int =
        records.count { taxonomy.node(it.taxonId).isLeaf && it.taxonId > 0 }

    /** Kept at genus or coarser, or at an indeterminate leaf — real records, not failures. */
    fun coarser(taxonomy: Taxonomy): Int = records.size - toSpecies(taxonomy)

    /** Distinct taxa, so ten photographs of one mallard is one entry in the list. */
    fun distinctTaxa(): Int = records.map { it.taxonId }.distinct().size
}

object LifeList {

    /** Which group a taxon belongs to: the first configured ancestor found walking up. */
    fun groupOf(
        taxonomy: Taxonomy,
        taxonId: Int,
        groups: List<Group> = DEFAULT_GROUPS,
    ): String {
        val lineage = taxonomy.lineage(taxonId).toSet()
        return groups.firstOrNull { it.taxonId in lineage }?.label ?: UNGROUPED
    }

    /**
     * Group the records, biggest group first, empty groups last.
     *
     * Empty groups are kept deliberately. "You haven't observed any amphibians yet" is the
     * thing that sends someone looking for amphibians, and it is the half of Seek's grouping
     * that actually does work.
     */
    fun tally(
        taxonomy: Taxonomy,
        records: List<Record>,
        groups: List<Group> = DEFAULT_GROUPS,
    ): List<GroupTally> {
        val byGroup = records.groupBy { groupOf(taxonomy, it.taxonId, groups) }
        val named = (groups.map { it.label } + UNGROUPED).distinct()
        return named
            .map { GroupTally(it, byGroup[it].orEmpty()) }
            .sortedWith(compareByDescending<GroupTally> { it.records.size }.thenBy { it.label })
    }

    /**
     * Every record sitting at or below a node — what "browsing at family" shows.
     *
     * §20: a record kept at *Carabus* stays visible when you look at Carabidae, beside a
     * species-level record from another day. No "unidentified" bucket off to one side, which
     * is where other apps put these and where nobody looks again.
     */
    fun under(taxonomy: Taxonomy, records: List<Record>, taxonId: Int): List<Record> =
        records.filter { taxonomy.isAncestorOrSelf(taxonId, it.taxonId) }

    /**
     * Three numbers, never one.
     *
     * Collapsing these into a single score is how a life list becomes a leaderboard, which
     * §7 rules out and §19 gives a reason for: a record kept at genus is a record, and it is
     * also not a species tick, and both of those need to stay true on screen.
     */
    data class Totals(val records: Int, val toSpecies: Int, val coarser: Int, val taxa: Int)

    fun totals(taxonomy: Taxonomy, records: List<Record>): Totals {
        val species = records.count { taxonomy.node(it.taxonId).isLeaf && it.taxonId > 0 }
        return Totals(
            records = records.size,
            toSpecies = species,
            coarser = records.size - species,
            taxa = records.map { it.taxonId }.distinct().size,
        )
    }

    /**
     * Refine a record to a deeper node, keeping the original determination in its history.
     *
     * Refusing to refine upward or sideways is the point: "a ground beetle" becoming
     * *Carabus granulatus* is new information, and *Carabus granulatus* becoming "a ground
     * beetle" is losing some.
     */
    fun refine(taxonomy: Taxonomy, record: Record, toTaxonId: Int, by: Determiner): Record {
        require(taxonomy.isAncestorOrSelf(record.taxonId, toTaxonId)) {
            "cannot refine ${record.taxonId} to $toTaxonId — it is not below the original"
        }
        require(toTaxonId != record.taxonId) { "already determined at $toTaxonId" }
        return record.copy(taxonId = toTaxonId, determinedBy = by, refinedFrom = record.taxonId)
    }
}
