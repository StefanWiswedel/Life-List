package dk.lifelist.core

/**
 * Narrowing a determination the model could not finish — spec §5.
 *
 * *"I tried to ID a wasp today and couldn't. A feature where it identifies to family or genus
 * and then asks for defining features (are the antenna yellow or black) to help ID further
 * would be amazing but I imagine very difficult."*
 *
 * Less difficult than it sounds, because **the hard half already exists**. The rollup stops at
 * genus precisely when it was choosing between a known, small, taxonomically coherent set of
 * species (§3.3). The app already knows that *Vespula vulgaris* against *V. germanica* was the
 * question; what it lacks is the answer, and the answer is one sentence.
 *
 * Two ways to get it, and which to offer is decided by **whether the evidence is still in front
 * of you**:
 *
 * - *The photograph you have not taken.* For *Vespula* the character is the clypeus — the face.
 *   Somebody standing next to the wasp should be told to photograph the face, not interrogated
 *   about it: the app already fuses several photographs of one individual (§3.2), a picture is
 *   evidence, and a memory is a guess wearing evidence's clothes.
 * - *The question, for when that is impossible.* An old photograph from the camera roll, an
 *   animal long gone. Then asking is all there is, and a weaker answer beats none.
 *
 * **A character is a likelihood, never a mask** — the rule the geo prior earned (§4A.4), and it
 * binds harder here. Wikipedia's own account of the *Vespula* clypeus says the mark "can
 * sometimes appear broken… making it look extremely similar", so an honest answer can still
 * describe the other species. Eliminating outright would make the atypical individual
 * unloggable, and the atypical individual is the one worth logging.
 *
 * Kept in `core/` beside the rollup, pinned against the Python reference by
 * `shared/golden/golden_characters.json`, for the same reason everything else here is: this
 * decides what a record says, and a screen is not a place to keep arithmetic.
 */
object Characters {

    /**
     * How sure a well-behaved character is.
     *
     * Not 1.0. A character that cannot be wrong does not exist in a field, and a 1.0 would let
     * one honest answer delete a species permanently.
     */
    const val DEFAULT_RELIABILITY = 0.92f

    /** What a species contradicted by the answer is multiplied by. Not zero, for the same reason. */
    const val CONTRARY = 0.06f

    /** One answer a person can give, and what it implies. */
    data class Option(
        /** What the person sees: "three small black dots". Not "clypeus with three maculae". */
        val label: String,
        val taxa: List<Int>,
        /**
         * How often a species showing this state is actually seen that way. Below the default
         * where the source says the character is variable — which is information a
         * hand-written key throws away and this one keeps.
         */
        val reliability: Float = DEFAULT_RELIABILITY,
    )

    /** One question, its answers, and where the claim came from. */
    data class Character(
        val key: String,
        /** "What does the face look like?" — the person is holding a phone, not a monograph. */
        val prompt: String,
        val options: List<Option>,
        /**
         * A URL. Every claim is quoted from somewhere, because a character written from memory
         * is an error nobody can trace, and this app's one unforgivable output is a confident
         * wrong answer.
         */
        val source: String = "",
        /** The caveat, where the source gives one. Shown, never swallowed. */
        val note: String = "",
    ) {
        val taxa: Set<Int> get() = options.flatMap { it.taxa }.toSet()
    }

    /** Everything known about telling one genus apart. */
    data class Guide(
        val taxonId: Int,
        /** The photograph that would settle it. Offered first when the animal may still be there. */
        val view: String = "",
        val characters: List<Character> = emptyList(),
    ) {
        /**
         * The questions that would actually split *these* candidates.
         *
         * One whose options all point at species already out of the running cannot change the
         * answer, and asking it spends the only thing this interaction has: patience.
         */
        fun applicable(candidates: Collection<Int>): List<Character> {
            val live = candidates.toSet()
            return characters.filter { character ->
                character.options.count { option -> option.taxa.any { it in live } } >= 2
            }
        }
    }

    /**
     * Fold one answer into the candidates. A likelihood, never a mask.
     *
     * A species the character says nothing about is **left exactly alone** — silence is not
     * contradiction, and treating it as such would quietly delete every candidate no key
     * happens to cover. That clause is the one that is easy to get wrong.
     */
    fun applyAnswer(
        probabilities: Map<Int, Float>,
        character: Character,
        chosen: String,
    ): Map<Int, Float> {
        val option = character.options.firstOrNull { it.label == chosen }
            ?: throw IllegalArgumentException("'$chosen' is not an option of '${character.key}'")
        val covered = character.taxa
        val named = option.taxa.toSet()
        return probabilities.mapValues { (taxon, p) ->
            when {
                taxon !in covered -> p
                taxon in named -> (p.toDouble() * option.reliability).toFloat()
                else -> (p.toDouble() * CONTRARY).toFloat()
            }
        }
    }

    /**
     * Back to a distribution, or unchanged when the answer has left nothing at all.
     *
     * Everything at zero means the answer rules out every candidate, which is a real outcome —
     * the determination was wrong further up — and must read as that rather than as a crash.
     */
    fun renormalise(probabilities: Map<Int, Float>): Map<Int, Float> {
        val total = probabilities.values.sumOf { it.toDouble() }
        if (total <= 0.0) return probabilities
        return probabilities.mapValues { (_, p) -> (p.toDouble() / total).toFloat() }
    }

    /**
     * The same update against the dense leaf vector the rollup consumes.
     *
     * Its own function rather than a conversion, because turning the vector into a map and
     * back for every answer is how a rounding difference gets into the golden fixture.
     */
    fun narrow(
        leafProbabilities: FloatArray,
        indexOf: Map<Int, Int>,
        character: Character,
        chosen: String,
    ): FloatArray {
        val option = character.options.firstOrNull { it.label == chosen }
            ?: throw IllegalArgumentException("'$chosen' is not an option of '${character.key}'")
        val named = option.taxa.toSet()
        val out = DoubleArray(leafProbabilities.size) { leafProbabilities[it].toDouble() }
        for (taxon in character.taxa) {
            val position = indexOf[taxon] ?: continue
            out[position] *= if (taxon in named) option.reliability.toDouble() else CONTRARY.toDouble()
        }
        val total = out.sum()
        if (total <= 0.0) return leafProbabilities.copyOf()
        return FloatArray(out.size) { (out[it] / total).toFloat() }
    }
}
