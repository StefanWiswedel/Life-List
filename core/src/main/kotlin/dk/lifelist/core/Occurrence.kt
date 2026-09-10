package dk.lifelist.core

/**
 * How often a species is recorded in Denmark, and how to say it without overclaiming.
 *
 * The Red List badge says whether a species is *threatened*, which is a different question from
 * whether finding one is notable, and it covers only the species somebody assessed. This is the
 * complement: GBIF's Danish occurrence count, aggregated over museum collections, atlas surveys
 * and citizen records rather than one app's users.
 *
 * **It measures recording effort, not abundance.** 686,000 blackbirds against 65,000 small
 * tortoiseshells does not mean ten times as many blackbirds — it means birds get recorded more.
 * So the number is never compared across the tree, only against the species' own family, where
 * the same people were looking in the same way. The wording follows: "recorded" throughout, and
 * never the word "rare", which is a claim about the animal rather than about the records.
 */
data class Occurrence(
    val taxonId: Int,
    /** Danish occurrence records. Zero means none at all, not missing data. */
    val records: Int,
    /** 1 is the most-recorded species of its family. */
    val rank: Int,
    /** Species of that family the app knows and has a count for. */
    val of: Int,
) {
    /** 0.0 for the most-recorded of its family, 1.0 for the least. */
    val fraction: Float get() = if (of <= 1) 0f else (rank - 1).toFloat() / (of - 1)

    /** True where the family is too small for a position in it to mean anything. */
    val alone: Boolean get() = of <= 2

    val band: Band
        get() = when {
            records == 0 -> Band.NONE
            alone -> Band.UNPLACED
            fraction >= 0.75f -> Band.SELDOM
            fraction <= 0.25f -> Band.WIDELY
            else -> Band.MIDDLING
        }

    enum class Band { NONE, SELDOM, MIDDLING, WIDELY, UNPLACED }
}

object Occurrences {

    /**
     * The line under the name.
     *
     * Deliberately plain about what it counts. A reader who thinks "115 records" is a statement
     * about how many of these exist in Denmark has been misled by us, so the sentence says
     * *recorded* every time, and the comparison names the family it is against.
     */
    fun phrase(occurrence: Occurrence, family: String?): String {
        val where = family?.let { " of the $it this app knows" } ?: " of its family"
        val n = grouped(occurrence.records)
        return when (occurrence.band) {
            Occurrence.Band.NONE -> "No Danish records at all"
            Occurrence.Band.UNPLACED -> "$n Danish records"
            Occurrence.Band.SELDOM -> "$n Danish records — among the least recorded$where"
            Occurrence.Band.WIDELY -> "$n Danish records — among the most recorded$where"
            Occurrence.Band.MIDDLING -> "$n Danish records"
        }
    }

    /**
     * Whether this is worth drawing attention to.
     *
     * Most species are unremarkable by this measure and a line on every one of them is noise
     * that teaches the reader to skip the place where the interesting ones appear.
     */
    fun notable(occurrence: Occurrence): Boolean =
        occurrence.band == Occurrence.Band.NONE || occurrence.band == Occurrence.Band.SELDOM

    /** 686000 -> "686,000". Thousands separated, because seven digits are unreadable raw. */
    fun grouped(value: Int): String =
        value.toString().reversed().chunked(3).joinToString(",").reversed()
}
