package dk.lifelist.core

import kotlin.math.pow

/**
 * Audio identification — Kotlin side of `shared/taxonomy-spec.md` §4A.
 *
 * `training/src/lifelist_train/audio.py` is the reference implementation and this must agree
 * with it exactly; `shared/golden/golden_audio.json` is the check, the same arrangement the
 * rollup has had since §5.1.
 *
 * The problem this solves: the vision head emits a softmax — one distribution over mutually
 * exclusive leaves, which is what §4's mass-summing rollup needs. **BirdNET does not.** It emits
 * independent per-class sigmoid confidences, and three species really can be singing at once.
 * Summing sigmoid scores up a tree gives numbers that look authoritative and mean nothing, which
 * is the precise failure this app exists to avoid.
 *
 * So detection and identification are separated. Detection stays multi-label, in BirdNET's own
 * semantics. Each detection is then resolved on its own against a taxonomically coherent
 * confusion set, renormalised into a proper conditional distribution — *given that this sound is
 * one of these, which is it?* — and handed to the same §4 rollup the camera path uses. Two
 * *Phylloscopus* candidates at 0.45 and 0.40 come back as the genus rather than a coin-flip
 * binomial, from exactly the same code.
 */
object Audio {

    const val DEFAULT_DETECTION_THRESHOLD = 0.25f
    const val DEFAULT_CONFUSION_MARGIN = 0.5f
    const val DEFAULT_GEO_WEIGHT = 1.0f

    /**
     * Confusion sets are only coherent within a family. A frog and a warbler calling at the same
     * moment are two detections, not two candidates for one identification.
     */
    val MAX_LCA_RANK: Int = RANK_ORDER.getValue("family")

    /** A range likelihood of zero must lower a species' odds, never make it unloggable. */
    private const val GEO_FLOOR = 1e-6

    /** One class over threshold in one window, before identification. */
    data class Detection(
        val taxonId: Int,
        val score: Float,
        val windowStartS: Float,
    )

    data class AudioIdentification(
        val detection: Detection,
        val result: RollupResult,
        val confusionSet: List<Int>,
        val geoApplied: Boolean,
        /** Pre-prior scores, kept so a suppressed vagrant stays recoverable (spec §4A.4). */
        val rawScores: Map<Int, Float>,
    )

    /** Spec §4A.1 — multi-label. Several species really can be singing at once. */
    fun detect(
        scores: Map<Int, Float>,
        windowStartS: Float = 0f,
        detectionThreshold: Float = DEFAULT_DETECTION_THRESHOLD,
    ): List<Detection> {
        require(detectionThreshold > 0f && detectionThreshold < 1f) {
            "detectionThreshold must be in (0, 1), got $detectionThreshold"
        }
        return scores.entries
            .filter { it.value >= detectionThreshold }
            .map { Detection(it.key, it.value, windowStartS) }
            // descending score, ties by lower taxonId — deterministic, as everywhere else
            .sortedWith(compareByDescending<Detection> { it.score }.thenBy { it.taxonId })
    }

    /**
     * Spec §4A.4 — a prior, never a mask.
     *
     * A hard range filter would make a genuine vagrant unloggable, which is exactly the record a
     * naturalist most wants. Weight 0 disables it.
     */
    fun applyGeoPrior(
        scores: Map<Int, Float>,
        geo: Map<Int, Float>,
        weight: Float = DEFAULT_GEO_WEIGHT,
    ): Map<Int, Float> {
        require(weight >= 0f) { "geoWeight must be non-negative, got $weight" }
        if (weight == 0f) return scores.toMap()
        return scores.mapValues { (taxonId, score) ->
            val likelihood = maxOf((geo[taxonId] ?: 0f).toDouble(), GEO_FLOOR)
            (score.toDouble() * likelihood.pow(weight.toDouble())).toFloat()
        }
    }

    /** Rank depth of the lowest common ancestor of two taxa. */
    fun lcaRankDepth(tax: Taxonomy, a: Int, b: Int): Int {
        val seen = tax.lineage(a).toSet()
        for (node in tax.lineage(b).reversed()) {
            if (node in seen) return RANK_ORDER.getValue(tax.node(node).rank)
        }
        return RANK_ORDER.getValue("root")
    }

    /** Spec §4A.2 — taxonomically coherent competitors scoring within [margin]. */
    fun confusionSet(
        tax: Taxonomy,
        scores: Map<Int, Float>,
        detection: Detection,
        margin: Float = DEFAULT_CONFUSION_MARGIN,
    ): List<Int> {
        require(margin > 0f && margin <= 1f) { "margin must be in (0, 1], got $margin" }
        val floor = detection.score * margin
        val members = mutableSetOf(detection.taxonId)
        for ((taxonId, score) in scores) {
            if (taxonId == detection.taxonId) continue
            if (score >= floor && lcaRankDepth(tax, detection.taxonId, taxonId) >= MAX_LCA_RANK) {
                members += taxonId
            }
        }
        return members.sorted()
    }

    /**
     * Resolve one detection to its deepest defensible rank (spec §4A.2–§4A.3).
     *
     * **The order is load-bearing.** The confusion set is built from RAW scores and the prior is
     * applied only afterwards, within the set. Applying it first would let a strong seasonal
     * prior push a candidate below the margin and out of the set altogether — a silent mask
     * wearing a prior's clothes, which §4A.4 forbids. Built this way, an out-of-season bird stays
     * on the card and is outvoted rather than erased.
     */
    fun identify(
        tax: Taxonomy,
        scores: Map<Int, Float>,
        detection: Detection,
        threshold: Float,
        margin: Float = DEFAULT_CONFUSION_MARGIN,
        geo: Map<Int, Float>? = null,
        geoWeight: Float = DEFAULT_GEO_WEIGHT,
    ): AudioIdentification {
        val raw = scores.toMap()

        val members = confusionSet(tax, raw, detection, margin)
        val effective = if (geo != null) applyGeoPrior(raw, geo, geoWeight) else raw

        val total = members.sumOf { effective.getValue(it).toDouble() }
        require(total > 0.0) { "confusion set has zero total score" }

        // Project onto the full leaf vector: everything outside the confusion set is conditioned
        // away, which is the point — the question is "given one of these, which?"
        val p = FloatArray(tax.nTaxa)
        for (member in members) {
            val leafIndex = tax.node(member).leafIndex
            requireNotNull(leafIndex) {
                "taxon $member is not a leaf and cannot carry a detection score"
            }
            p[leafIndex] = (effective.getValue(member).toDouble() / total).toFloat()
        }

        return AudioIdentification(
            detection = detection,
            result = Rollup.rollup(tax, p, threshold),
            confusionSet = members,
            geoApplied = geo != null && geoWeight > 0f,
            rawScores = members.associateWith { raw.getValue(it) },
        )
    }

    /** Full pipeline for one window: detect, then identify each detection separately. */
    fun identifyWindow(
        tax: Taxonomy,
        scores: Map<Int, Float>,
        threshold: Float,
        windowStartS: Float = 0f,
        detectionThreshold: Float = DEFAULT_DETECTION_THRESHOLD,
        margin: Float = DEFAULT_CONFUSION_MARGIN,
        geo: Map<Int, Float>? = null,
        geoWeight: Float = DEFAULT_GEO_WEIGHT,
    ): List<AudioIdentification> =
        detect(scores, windowStartS, detectionThreshold).map {
            identify(tax, scores, it, threshold, margin, geo, geoWeight)
        }
}
