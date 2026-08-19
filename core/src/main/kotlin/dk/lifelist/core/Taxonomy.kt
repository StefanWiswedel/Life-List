package dk.lifelist.core

/**
 * Taxonomic tree — Kotlin implementation of `shared/taxonomy-spec.md` §1.
 *
 * This module is deliberately pure Kotlin/JVM with no Android dependencies, so the
 * parity tests against the Python reference run in CI in seconds without an emulator
 * or the Android SDK. The rollup is the thing this app exists for; it should be the
 * cheapest thing in the repo to test.
 */

const val SPEC_VERSION = 1
const val ROOT_ID = 0

/** Ordered coarsest-first. `speciesAggregate` sits at the same depth as `species`. */
val RANK_ORDER: Map<String, Int> = mapOf(
    "root" to 0,
    "kingdom" to 1,
    "phylum" to 2,
    "class" to 3,
    "order" to 4,
    "family" to 5,
    "genus" to 6,
    "species" to 7,
    "species_aggregate" to 7,
    "subspecies" to 8,
)

class TaxonomyException(message: String) : IllegalArgumentException(message)

data class Taxon(
    val taxonId: Int,
    val parentId: Int?,
    val rank: String,
    val scientificName: String,
    val vernacularDa: String? = null,
    val vernacularEn: String? = null,
    val leafIndex: Int? = null,
) {
    val isLeaf: Boolean get() = leafIndex != null
}

class Taxonomy(taxa: List<Taxon>) {

    val nodes: Map<Int, Taxon>
    val rootId: Int

    private val childrenById: Map<Int, List<Int>>
    private val subtreeLeaves: Map<Int, IntArray>
    private val leafIdByIndex: IntArray

    init {
        nodes = taxa.associateBy { it.taxonId }
        if (nodes.size != taxa.size) throw TaxonomyException("duplicate taxon_id")

        val roots = taxa.filter { it.parentId == null }
        if (roots.size != 1) {
            throw TaxonomyException("expected exactly one root, found ${roots.size}")
        }
        rootId = roots.single().taxonId

        // Children sorted ascending by taxon_id so tie-breaking (spec §4.2) and float
        // accumulation order (spec §4.1) are deterministic and match Python.
        childrenById = taxa
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
            .mapValues { (_, kids) -> kids.map { it.taxonId }.sorted() }

        validate(taxa)

        leafIdByIndex = IntArray(taxa.count { it.isLeaf })
        taxa.filter { it.isLeaf }.forEach { leafIdByIndex[it.leafIndex!!] = it.taxonId }

        val acc = HashMap<Int, IntArray>(nodes.size)
        buildSubtreeLeaves(rootId, acc)
        subtreeLeaves = acc
    }

    private fun validate(taxa: List<Taxon>) {
        // invariant 2 — acyclic and connected
        for (taxon in taxa) {
            val seen = HashSet<Int>()
            var cur: Int? = taxon.taxonId
            while (cur != null) {
                if (!seen.add(cur)) throw TaxonomyException("cycle detected at taxon $cur")
                val node = nodes[cur]
                    ?: throw TaxonomyException("taxon ${taxon.taxonId} references missing parent $cur")
                cur = node.parentId
            }
            if (rootId !in seen) {
                throw TaxonomyException("taxon ${taxon.taxonId} does not reach root")
            }
        }

        // invariant 4 — leafIndex set iff no children
        val leafIndices = ArrayList<Int>()
        for (taxon in taxa) {
            val hasChildren = !childrenById[taxon.taxonId].isNullOrEmpty()
            if (hasChildren == taxon.isLeaf) {
                throw TaxonomyException(
                    "taxon ${taxon.taxonId}: leaf_index must be set iff the node has no " +
                        "children (hasChildren=$hasChildren, leafIndex=${taxon.leafIndex})"
                )
            }
            taxon.leafIndex?.let { leafIndices.add(it) }
        }

        // invariant 3 — leaf indices are exactly 0..N-1
        if (leafIndices.sorted() != leafIndices.indices.toList()) {
            throw TaxonomyException("leaf_index values must be exactly 0..N_taxa-1, each used once")
        }

        // invariant 5 — child rank strictly deeper than parent
        for (taxon in taxa) {
            val parent = taxon.parentId?.let { nodes[it] } ?: continue
            val childRank = RANK_ORDER[taxon.rank]
                ?: throw TaxonomyException("taxon ${taxon.taxonId}: unknown rank '${taxon.rank}'")
            val parentRank = RANK_ORDER[parent.rank]
                ?: throw TaxonomyException("taxon ${parent.taxonId}: unknown rank '${parent.rank}'")
            if (childRank <= parentRank) {
                throw TaxonomyException(
                    "taxon ${taxon.taxonId} (${taxon.rank}) is not deeper than " +
                        "parent ${parent.taxonId} (${parent.rank})"
                )
            }
        }
    }

    private fun buildSubtreeLeaves(taxonId: Int, acc: MutableMap<Int, IntArray>): IntArray {
        val node = nodes.getValue(taxonId)
        val result = if (node.isLeaf) {
            intArrayOf(node.leafIndex!!)
        } else {
            val collected = ArrayList<Int>()
            for (child in children(taxonId)) collected.addAll(buildSubtreeLeaves(child, acc).toList())
            collected.sort() // spec §4.1 — ascending leaf_index accumulation order
            collected.toIntArray()
        }
        acc[taxonId] = result
        return result
    }

    val nTaxa: Int get() = leafIdByIndex.size

    /**
     * The node, or an exception.
     *
     * Correct for the rollup, which operates on ids it produced itself from this very tree —
     * a miss there is a broken invariant and should be loud. Wrong for anything reading a
     * *stored* id, because a record outlives the model that made it. Use [nodeOrNull] there.
     */
    fun node(taxonId: Int): Taxon = nodes.getValue(taxonId)

    /**
     * The node, or null if this tree has never heard of it.
     *
     * Added after v0.7.1 crashed on launch for everyone with a saved record. The immediate
     * cause was the home screen being handed the *demo* taxonomy, but the deeper one outlives
     * that bug: a life list is permanent and a taxonomy is a build artefact. Retrain with a
     * different occurrence threshold and some taxa leave; every record of one then referred to
     * a node that no longer existed, and `getValue` turned that into a crash on every launch
     * with no way back except deleting the app's data.
     *
     * A collection must never be destroyed by the model changing under it.
     */
    fun nodeOrNull(taxonId: Int): Taxon? = nodes[taxonId]

    operator fun contains(taxonId: Int): Boolean = taxonId in nodes

    fun children(taxonId: Int): List<Int> = childrenById[taxonId].orEmpty()

    fun leafId(leafIndex: Int): Int = leafIdByIndex[leafIndex]

    fun subtreeLeafIndices(taxonId: Int): IntArray = subtreeLeaves.getValue(taxonId)

    /** Root-first path from root to [taxonId] inclusive. Empty if the node is unknown. */
    fun lineage(taxonId: Int): List<Int> {
        val out = ArrayList<Int>()
        var cur: Int? = taxonId
        while (cur != null) {
            val node = nodes[cur] ?: return emptyList()
            out.add(cur)
            cur = node.parentId
        }
        return out.asReversed()
    }

    /**
     * True if [candidate] lies on the root-to-[target] path.
     *
     * The predicate behind the rollup-accuracy metric: returning `Carabus` for a
     * *Carabus granulatus* is a correct answer, not a near miss.
     */
    fun isAncestorOrSelf(candidate: Int, target: Int): Boolean {
        var cur: Int? = target
        while (cur != null) {
            if (cur == candidate) return true
            cur = nodes[cur]?.parentId ?: return false
        }
        return false
    }
}
