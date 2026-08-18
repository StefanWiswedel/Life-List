package dk.lifelist.core

/**
 * Turning a [RollupResult] into something a human reads.
 *
 * This is the surface where the product's one claim either lands or does not. Arter says
 * "Pelurga comitata, 80% sikker" and stops; the whole argument for this app is that when the
 * evidence only supports "a geometer moth", it says that instead — and says it in a way that
 * reads as an answer rather than as a failure.
 *
 * So the wording is not decoration, and it is not left to the Compose layer. It is here, in
 * pure Kotlin with no Android dependency, because it is testable and because two screens
 * rendering the same result differently is how a careful hedge turns into a confident-sounding
 * species name in one of them.
 *
 * Typography rules come from `shared/taxonomy-spec.md` §1.2 and BUILD.md §7. Nomenclature is
 * not styling: *Anas platyrhynchos* is italic because it is a binomial, `agg.` stays roman
 * because it is not part of the name, and Anatidae is roman because ranks above genus are.
 * Getting that wrong is the kind of thing a naturalist notices immediately and everyone else
 * never does, which is exactly why it belongs in a test rather than in a designer's memory.
 */

/** A run of text that is either italic or not. Names are built from these, never from a flag. */
data class NameRun(val text: String, val italic: Boolean)

/** How the rollup answered. */
enum class AnswerKind {
    /** Landed on a leaf — the deepest thing the taxonomy models. */
    LEAF,

    /**
     * Landed on an indeterminate leaf — `Carabus sp.`, spec §1.1a.
     *
     * Structurally a leaf, and rank `species`, but it is a *genus-level* determination.
     * Treating it as [LEAF] made the app say "confident at species level" about a
     * specimen it had explicitly declined to identify to species, which is the exact
     * overclaim this whole app exists to avoid. Caught by building the screen.
     */
    INDETERMINATE,

    /** Stopped above the leaves. The interesting case, and the reason this app exists. */
    HIGHER_RANK,

    /** Landed at root. Nothing defensible to say. */
    UNIDENTIFIED,
}

data class Confidence(
    val probability: Float,
    /** Tabular figures, no decimals — "71%". BUILD.md §7. */
    val percent: String,
    /** 0..1, for the thin horizontal bar. Never exceeds 1 even if probability does. */
    val barFraction: Float,
)

/** One step of the printed-key lineage, root-first. */
data class LineageStep(
    val taxonId: Int,
    val rank: String,
    val name: List<NameRun>,
    /** True for the node the rollup actually returned. */
    val isAnswer: Boolean,
)

data class CandidateLine(
    val taxonId: Int,
    val name: List<NameRun>,
    val vernacular: String?,
    val confidence: Confidence,
    /** True when this leaf sits under the returned node. */
    val withinAnswer: Boolean,
)

data class Answer(
    val kind: AnswerKind,
    val taxonId: Int,
    val rank: String,
    /** The scientific name, correctly styled. Always present except when unidentified. */
    val scientificName: List<NameRun>,
    /** English vernacular if the taxonomy has one. Danish is stored but not surfaced. */
    val vernacular: String?,
    /** "genus", "family" — null for a leaf answer, where the rank is not the point. */
    val rankLabel: String?,
    /** One honest sentence about what this answer does and does not claim. */
    val explanation: String,
    val confidence: Confidence,
    val lineage: List<LineageStep>,
    val candidates: List<CandidateLine>,
)

object Presentation {

    /** Ranks at or below genus are italicised; anything coarser is not. */
    private const val ITALIC_FROM = "genus"

    /** Suffixes that are notation rather than name, and stay roman inside an italic binomial. */
    private val ROMAN_SUFFIXES = listOf("agg.", "sp.", "spp.", "cf.", "aff.", "×")

    fun isItalicRank(rank: String): Boolean {
        val depth = RANK_ORDER[rank] ?: return false
        val threshold = RANK_ORDER.getValue(ITALIC_FROM)
        return depth >= threshold
    }

    /**
     * Split a scientific name into italic and roman runs.
     *
     * `Taraxacum officinale agg.` is two italic words and a roman abbreviation, per §1.2. The
     * naive alternative — italicising the whole string — is wrong in a way that is invisible
     * until a botanist looks at it.
     */
    fun styleName(scientificName: String, rank: String): List<NameRun> {
        val italic = isItalicRank(rank)
        if (!italic) return listOf(NameRun(scientificName, italic = false))

        val runs = ArrayList<NameRun>()
        val pending = StringBuilder()

        fun flush(asItalic: Boolean) {
            if (pending.isNotEmpty()) {
                runs.add(NameRun(pending.toString().trim(), asItalic))
                pending.clear()
            }
        }

        for (word in scientificName.split(" ").filter { it.isNotBlank() }) {
            if (word in ROMAN_SUFFIXES) {
                flush(asItalic = true)
                runs.add(NameRun(word, italic = false))
            } else {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(word)
            }
        }
        flush(asItalic = true)
        return runs.filter { it.text.isNotEmpty() }
    }

    fun confidence(probability: Float): Confidence =
        Confidence(
            probability = probability,
            percent = "${Math.round(probability * 100f).coerceIn(0, 100)}%",
            barFraction = probability.coerceIn(0f, 1f),
        )

    /**
     * The sentence under the name.
     *
     * Deliberately factual rather than apologetic. "Confident only to genus" frames a correct
     * answer as a shortfall; the honest framing is that the genus *is* the answer and the
     * species is what the evidence will not support. A user who is told the truth plainly
     * trusts the app more than one who is told it failed.
     *
     * These strings are a proposal, not a decision — BUILD.md §8 says to raise real forks
     * rather than invent them, and final copy is a fork. They live in one place so changing
     * them is one edit and one test.
     */
    fun explain(
        kind: AnswerKind,
        rank: String,
        threshold: Float,
        parentName: String? = null,
    ): String =
        when (kind) {
            AnswerKind.LEAF ->
                "Confident at species level."

            AnswerKind.INDETERMINATE ->
                "Confident to genus${parentName?.let { " — this is a $it" } ?: ""}, and the " +
                    "species is not determined. That is the answer the evidence supports, " +
                    "not a shortfall."

            AnswerKind.HIGHER_RANK ->
                "This is a $rank-level answer. The species below it are too close to " +
                    "separate at the ${Math.round(threshold * 100f)}% confidence you asked for."

            AnswerKind.UNIDENTIFIED ->
                "Not enough evidence to place this anywhere with confidence. " +
                    "A clearer photo, or a second one from another angle, is the usual fix."
        }

    /**
     * Build the display model.
     *
     * Candidates are shown even when the rollup stopped above them, and the ones outside the
     * returned node's subtree are marked rather than hidden — §4.3 is explicit that a
     * naturalist wants the runner-up genus, and suppressing it would be the opacity the spec
     * forbids.
     */
    fun present(taxonomy: Taxonomy, result: RollupResult): Answer {
        val kind = when {
            result.isUnidentified -> AnswerKind.UNIDENTIFIED
            result.taxonId < 0 -> AnswerKind.INDETERMINATE
            taxonomy.node(result.taxonId).isLeaf -> AnswerKind.LEAF
            else -> AnswerKind.HIGHER_RANK
        }

        val node = taxonomy.node(result.taxonId)
        val parent = node.parentId?.let { taxonomy.node(it) }
        val lineageIds = taxonomy.lineage(result.taxonId)

        val candidates = result.candidates.map { candidate ->
            val leaf = taxonomy.node(candidate.taxonId)
            CandidateLine(
                taxonId = candidate.taxonId,
                name = styleName(leaf.scientificName, leaf.rank),
                vernacular = leaf.vernacularEn,
                confidence = confidence(candidate.probability),
                withinAnswer = taxonomy.isAncestorOrSelf(result.taxonId, candidate.taxonId),
            )
        }

        return Answer(
            kind = kind,
            taxonId = result.taxonId,
            rank = result.rank,
            scientificName =
                if (kind == AnswerKind.UNIDENTIFIED) emptyList()
                else styleName(node.scientificName, node.rank),
            vernacular = if (kind == AnswerKind.UNIDENTIFIED) null else node.vernacularEn,
            rankLabel = when (kind) {
                AnswerKind.HIGHER_RANK -> result.rank
                // The useful label is the rank actually determined, not the synthetic
                // node's own `species`.
                AnswerKind.INDETERMINATE -> parent?.rank
                else -> null
            },
            explanation = explain(kind, result.rank, result.threshold, parent?.scientificName),
            confidence = confidence(result.probability),
            lineage = lineageIds.map { id ->
                val step = taxonomy.node(id)
                LineageStep(
                    taxonId = id,
                    rank = step.rank,
                    name = styleName(step.scientificName, step.rank),
                    isAnswer = id == result.taxonId,
                )
            },
            candidates = candidates,
        )
    }

    /** Plain text of a styled name, for search, sharing and accessibility labels. */
    fun plain(runs: List<NameRun>): String = runs.joinToString(" ") { it.text }.trim()
}
