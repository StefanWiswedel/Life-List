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
    /**
     * Every photograph of this individual, in the order they were taken.
     *
     * A list rather than one path because several angles are what settles a hard insect, and
     * because a sighting you later add a photo to is the same sighting. The first is the one
     * the list shows.
     */
    val photoPaths: List<String> = emptyList(),
    /** The threshold in force when this was determined, so §4.4 can re-render it honestly. */
    val threshold: Float,
    val modelVersion: String,
    val determinedBy: Determiner,
    /** Set when a record was later refined; the original determination is never overwritten. */
    val refinedFrom: Int? = null,
    /**
     * What the model said at the moment it was kept.
     *
     * Stored rather than recomputed: the model will be replaced, and a record that silently
     * re-scores itself under a newer model is a record that lies about what you saw and what
     * you were told at the time. Null for a record the user determined themselves — a tap is
     * not a probability (§20).
     */
    val confidence: Float? = null,
    /** Where, if the device knew and the user allowed it. Coarse, and never required. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** "Vanløse, Copenhagen" — reverse-geocoded once, at the time, and then left alone. */
    val place: String? = null,
) {
    /** The photograph that stands for this record. */
    val photoPath: String? get() = photoPaths.firstOrNull()
}

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
        records.count { taxonomy.nodeOrNull(it.taxonId)?.isLeaf == true && it.taxonId > 0 }

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
        // `lineage` returns empty for a taxon this tree does not contain, so an orphaned
        // record lands in UNGROUPED rather than taking the app down with it.
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
        val species = records.count {
            taxonomy.nodeOrNull(it.taxonId)?.isLeaf == true && it.taxonId > 0
        }
        return Totals(
            records = records.size,
            toSpecies = species,
            coarser = records.size - species,
            taxa = records.map { it.taxonId }.distinct().size,
        )
    }

    /**
     * Is this taxon new to the list?
     *
     * The one number a collection app owes its user. A life list is a record of firsts, and an
     * app that stores forty sightings without ever saying "you have not seen this before" has
     * thrown away the only thing that makes the fortieth as good as the first.
     *
     * Deliberately *not* "have you seen anything in this genus" — a mallard after a teal is a
     * first, and pretending otherwise to make the badge rarer would be a lie about the list.
     */
    fun isFirst(records: List<Record>, taxonId: Int): Boolean =
        records.none { it.taxonId == taxonId }

    /**
     * The last few sightings, newest first, one per taxon.
     *
     * One per taxon because a rail of six photographs of the same blackbird is a rail that
     * says nothing. `distinctBy` runs after the sort, so the newest photograph of a taxon is
     * the one that survives.
     */
    fun recent(records: List<Record>, limit: Int = 8): List<Record> =
        records.sortedByDescending { it.observedAt }.distinctBy { it.taxonId }.take(limit)

    /**
     * The leaves a hedged answer was choosing between — the ones the user can settle.
     *
     * Built from the **full probability vector**, not from `RollupResult.candidates`. That
     * list is the global top five, and a genus can easily hold one of them and no more: a
     * real identification of *Yponomeuta* at 71% showed a single species below it and so was
     * offered no question at all, while a family-level answer two taps earlier offered three.
     * Same code, different truncation, and it read as a bug because it was one.
     *
     * Indeterminate leaves are excluded. `Yponomeuta sp.` *is* the genus-level answer; putting
     * it in the list of species to choose between would be offering the question as one of its
     * own answers.
     *
     * A single contender is still worth offering — "is it this one?" is a question a naturalist
     * can answer, and refusing to ask it is how the ermine moth ended up mute.
     */
    fun choices(
        taxonomy: Taxonomy,
        probabilities: FloatArray,
        taxonId: Int,
        limit: Int = 3,
        floor: Float = 0.01f,
    ): List<Candidate> {
        if (taxonId == ROOT_ID) return emptyList()
        val node = taxonomy.nodeOrNull(taxonId) ?: return emptyList()
        if (node.isLeaf) return emptyList()

        return taxonomy.subtreeLeafIndices(taxonId)
            .map { index -> Candidate(taxonomy.leafId(index), index, probabilities[index]) }
            // A negative id is the synthetic `X sp.` leaf — the hedge itself, not a rival.
            .filter { it.taxonId > 0 && it.probability >= floor }
            .sortedWith(compareByDescending<Candidate> { it.probability }.thenBy { it.taxonId })
            .take(limit)
    }

    /**
     * Every species sitting under a node, for settling a record by hand later.
     *
     * The probabilities are long gone by the time someone looks a record up again — only the
     * single number that was true at the time is stored (§28) — so a later refinement is a
     * *choice*, not a re-run. This is the list to choose from, alphabetical because that is
     * how someone scans for a name they have since looked up in a book.
     */
    fun speciesUnder(taxonomy: Taxonomy, taxonId: Int): List<Taxon> =
        if (taxonId !in taxonomy) emptyList()
        else taxonomy.subtreeLeafIndices(taxonId)
            .map { taxonomy.node(taxonomy.leafId(it)) }
            .filter { it.taxonId != taxonId }
            .sortedBy { it.vernacularEn ?: it.scientificName }

    /**
     * Refine a record to a deeper node, keeping the original determination in its history.
     *
     * Refusing to refine upward or sideways is the point: "a ground beetle" becoming
     * *Carabus granulatus* is new information, and *Carabus granulatus* becoming "a ground
     * beetle" is losing some.
     */
    fun refine(taxonomy: Taxonomy, record: Record, toTaxonId: Int, by: Determiner): Record {
        require(toTaxonId in taxonomy) { "$toTaxonId is not in this taxonomy" }
        require(taxonomy.isAncestorOrSelf(record.taxonId, toTaxonId)) {
            "cannot refine ${record.taxonId} to $toTaxonId — it is not below the original"
        }
        require(toTaxonId != record.taxonId) { "already determined at $toTaxonId" }
        return record.copy(taxonId = toTaxonId, determinedBy = by, refinedFrom = record.taxonId)
    }
}
