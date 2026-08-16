package dk.lifelist.core

/**
 * Taxonomic rollup — Kotlin implementation of `shared/taxonomy-spec.md` §4.
 *
 * Must produce output identical to `training/src/lifelist_train/rollup.py` for identical
 * input. `shared/golden/golden_rollup.json` is the cross-language check; see
 * `RollupGoldenTest`.
 *
 * Serves both modalities. Vision hands it a softmax; audio hands it a conditional
 * distribution built from a confusion set (spec §4A). Neither path gets its own copy.
 */

const val DEFAULT_THRESHOLD = 0.70f
const val MIN_THRESHOLD = 0.50f
const val MAX_THRESHOLD = 0.95f
const val N_CANDIDATES = 5

data class Candidate(
    val taxonId: Int,
    val leafIndex: Int,
    val probability: Float,
)

data class RollupResult(
    val taxonId: Int,
    val rank: String,
    val probability: Float,
    val candidates: List<Candidate>,
    val threshold: Float,
) {
    /** Nothing cleared threshold even at kingdom level (spec §4.2). */
    val isUnidentified: Boolean get() = rank == "root"
}

object Rollup {

    /**
     * P(n) for every node: the sum of its descendant leaves (spec §4.1).
     *
     * Accumulated in ascending leafIndex order in [Double], then narrowed — the
     * determinism requirement. Float addition is not associative, so summing in a
     * different order from Python would drift the golden test for no visible reason.
     */
    fun nodeProbabilities(tax: Taxonomy, p: FloatArray): Map<Int, Double> {
        checkLeafProbabilities(tax, p)
        val out = HashMap<Int, Double>(tax.nodes.size)
        for (taxonId in tax.nodes.keys) {
            var sum = 0.0
            for (leafIndex in tax.subtreeLeafIndices(taxonId)) sum += p[leafIndex].toDouble()
            out[taxonId] = sum
        }
        return out
    }

    /**
     * Descend to the deepest node whose probability clears [threshold].
     *
     * Returns the root when nothing clears it, which the UI renders as "cannot identify"
     * rather than as a bad guess.
     */
    fun rollup(
        tax: Taxonomy,
        p: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD,
        nCandidates: Int = N_CANDIDATES,
    ): RollupResult {
        require(threshold in MIN_THRESHOLD..MAX_THRESHOLD) {
            "threshold $threshold outside the settable range [$MIN_THRESHOLD, $MAX_THRESHOLD]"
        }
        val probs = nodeProbabilities(tax, p)

        var nodeId = tax.rootId
        while (true) {
            val children = tax.children(nodeId)
            if (children.isEmpty()) break
            // Children are sorted ascending by taxonId, and the comparison is strictly
            // greater — so an exact tie keeps the lower id (spec §4.2).
            var best = children.first()
            for (child in children.drop(1)) {
                if (probs.getValue(child) > probs.getValue(best)) best = child
            }
            // >= not >, so exactly-at-threshold descends (spec §4.2).
            if (probs.getValue(best) >= threshold) nodeId = best else break
        }

        return RollupResult(
            taxonId = nodeId,
            rank = tax.node(nodeId).rank,
            probability = probs.getValue(nodeId).toFloat(),
            candidates = topCandidates(tax, p, nCandidates),
            threshold = threshold,
        )
    }

    /**
     * Top-[n] leaves by probability, descending; ties break by lower taxonId.
     *
     * Deliberately not restricted to the returned node's subtree (spec §4.3) — a
     * naturalist wants to see the runner-up genus, and hiding it because the rollup
     * stopped higher would be exactly the opacity this app is against.
     */
    fun topCandidates(tax: Taxonomy, p: FloatArray, n: Int = N_CANDIDATES): List<Candidate> =
        p.indices
            .sortedWith(compareByDescending<Int> { p[it] }.thenBy { tax.leafId(it) })
            .take(n)
            .map { Candidate(taxonId = tax.leafId(it), leafIndex = it, probability = p[it]) }

    /**
     * Rollup accuracy predicate: is the answer an honest ancestor of the truth?
     *
     * A refusal scores false. Otherwise an app that always says "don't know" would
     * report 100% accuracy.
     */
    fun isRollupCorrect(tax: Taxonomy, result: RollupResult, trueLeafId: Int): Boolean =
        !result.isUnidentified && tax.isAncestorOrSelf(result.taxonId, trueLeafId)

    private fun checkLeafProbabilities(tax: Taxonomy, p: FloatArray) {
        require(p.size == tax.nTaxa) {
            "probability vector has ${p.size} entries but the taxonomy has ${tax.nTaxa} leaves"
        }
        require(p.all { it >= 0f }) { "probability vector contains negative entries" }
        val total = p.fold(0.0) { acc, v -> acc + v }
        require(kotlin.math.abs(total - 1.0) < 1e-4) {
            "probability vector sums to $total, expected 1.0"
        }
    }
}
