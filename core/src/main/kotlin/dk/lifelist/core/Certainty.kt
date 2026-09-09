package dk.lifelist.core

/**
 * Turning the dial into a promise.
 *
 * The threshold is a summed probability, and "I want the summed probability to clear 0.70" is
 * not a sentence anybody can hold an opinion about. "I want to be right 95% of the time" is.
 * The training side fits, per group and per target accuracy, the lowest threshold that reaches
 * it (`training/src/lifelist_train/thresholds.py`); this reads that table back.
 *
 * The group has to be the **predicted** one. At the moment of choosing a threshold, the truth
 * is what is unknown, so the group comes from the top-scoring leaf — the same choice the fit
 * was measured under. Splitting on the true group would report a number measured under
 * conditions that never occur.
 *
 * Four of the nine groups cannot reach 95% at any threshold, so [Certainty] carries what the
 * setting actually delivers rather than only the number it sets. A screen that said "95%" over
 * a model managing 89.7% would be the exact dishonesty this feature exists to remove.
 */
data class GroupThreshold(
    val threshold: Float,
    /** Rollup accuracy measured at that threshold on held-out photographs. */
    val accuracy: Float,
    /** False when no threshold reaches the target and this is merely the best on offer. */
    val reached: Boolean,
    /** Test photographs behind the number. 497 for mammals; 17,531 for insects. */
    val n: Int,
)

/**
 * What the app is promising for one identification, and whether it can keep the promise.
 *
 * [delivered] is null for a group the fit left out — fewer than 100 test photographs is not
 * evidence, and inventing a threshold from forty examples would dress noise up as calibration.
 * Those fall back to [DEFAULT_THRESHOLD] and say so.
 */
data class Certainty(
    val group: String,
    val target: Float,
    val threshold: Float,
    val delivered: Float?,
    val reached: Boolean,
    val n: Int,
) {
    /** True when the group was never measured, so the app is using the global default. */
    val measured: Boolean get() = delivered != null
}

/** The fitted table, as shipped in `model_meta.json` under `group_thresholds`. */
class CertaintyTable(private val byTarget: Map<Float, Map<String, GroupThreshold>>) {

    /** The accuracies a person can ask for, ascending. */
    val targets: List<Float> = byTarget.keys.sorted()

    val isEmpty: Boolean get() = byTarget.isEmpty()

    /**
     * The nearest fitted target at or above [target], or the highest there is.
     *
     * A stored preference outlives the model that answered it: a phone holding 0.98 against a
     * table fitted at 0.90 and 0.95 must still do something sensible rather than fall back to
     * the global default and quietly become less careful than the user asked.
     */
    fun resolveTarget(target: Float): Float? =
        targets.firstOrNull { it >= target - 1e-4f } ?: targets.lastOrNull()

    fun forGroup(target: Float, group: String): GroupThreshold? =
        resolveTarget(target)?.let { byTarget[it]?.get(group) }

    /**
     * What to use, and what to say, for a photograph whose best guess is [group].
     */
    fun certaintyFor(target: Float, group: String): Certainty {
        val resolved = resolveTarget(target) ?: target
        val fitted = forGroup(target, group)
        return Certainty(
            group = group,
            target = resolved,
            threshold = fitted?.threshold ?: DEFAULT_THRESHOLD,
            delivered = fitted?.accuracy,
            reached = fitted?.reached ?: false,
            n = fitted?.n ?: 0,
        )
    }

    companion object {
        val EMPTY = CertaintyTable(emptyMap())
    }
}

/** A rollup taken at the threshold the requested accuracy costs, and what that accuracy is. */
data class TargetedRollup(val result: RollupResult, val certainty: Certainty)

object Certainties {

    /**
     * Roll up at whatever threshold this group needs to hit [target].
     *
     * The group is read off the highest-scoring leaf before any rollup happens, because the
     * threshold is an input to the rollup and cannot depend on its output.
     */
    fun rollup(
        taxonomy: Taxonomy,
        p: FloatArray,
        table: CertaintyTable,
        target: Float,
        groups: List<Group> = DEFAULT_GROUPS,
    ): TargetedRollup {
        val certainty = table.certaintyFor(target, predictedGroup(taxonomy, p, groups))
        return TargetedRollup(
            result = Rollup.rollup(taxonomy, p, certainty.threshold),
            certainty = certainty,
        )
    }

    /** The group of the top-scoring leaf. Ties go to the lower taxon id, as everywhere else. */
    fun predictedGroup(
        taxonomy: Taxonomy,
        p: FloatArray,
        groups: List<Group> = DEFAULT_GROUPS,
    ): String {
        require(p.isNotEmpty()) { "cannot name a group for an empty probability vector" }
        var best = 0
        for (i in 1 until p.size) {
            if (p[i] > p[best]) best = i
        }
        return LifeList.groupOf(taxonomy, taxonomy.leafId(best), groups)
    }
}
