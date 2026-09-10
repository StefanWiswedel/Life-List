package dk.lifelist.core

/**
 * How much of a family you have found.
 *
 * "12 of 310 Geometridae" is the sentence this exists to produce, and the interesting word is
 * *310*. Counting against the model's own vocabulary would say 147, which measures how much
 * training data we could scrape rather than what is out there, and would change under the
 * reader every time the model is retrained. The Danish Red List assesses 13,899 species and
 * records a family for each, so the denominator can be a fact about Denmark instead.
 *
 * Family is a fixed rank, chosen over "the smallest clade with enough members". The rule that
 * walks up until a clade is big enough produces better-sized groups and a worse app: the reader
 * has to work out what level each number is at before it means anything. Family is what field
 * guides are organised by, and being occasionally awkward — three Canidae — is a smaller cost
 * than being inconsistent.
 */
object Families {

    /** Where a denominator came from. The app says different sentences for the two. */
    enum class Source {
        /** The Danish Red List: how many species Denmark has. */
        DENMARK,

        /** Our own vocabulary, for families the Red List does not cover. */
        APP,
    }

    data class Progress(
        val familyId: Int,
        val scientificName: String,
        val vernacularEn: String?,
        /** Distinct species in this family on your list. */
        val seen: Int,
        val total: Int,
        val source: Source,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else seen.toFloat() / total
        val complete: Boolean get() = total > 0 && seen >= total
    }

    /** The family a taxon sits in, or null — two of 3,482 leaves genuinely have no family. */
    fun familyOf(taxonomy: Taxonomy, taxonId: Int): Taxon? =
        taxonomy.lineage(taxonId).asReversed()
            .mapNotNull { taxonomy.nodes[it] }
            .firstOrNull { it.rank == "family" }

    /**
     * Species of this family the app can recognise. The floor under a Danish total, never
     * the number shown when a Danish one exists.
     *
     * **Synthetic `sp.` leaves do not count.** `Forficula sp.` is a class the head can be
     * trained on (§1.1a), not a species anybody can go and find, and [seenIn] has always
     * excluded it from the numerator — so counting it in the denominator made "1 of 3" a
     * fraction with two different kinds of thing in it, and a third of that family
     * permanently unfindable. 19 of 691 families were affected (VERIFICATION §68).
     */
    fun knownToApp(taxonomy: Taxonomy, familyId: Int): Int =
        taxonomy.subtreeLeafIndices(familyId).count { taxonomy.leafId(it) > 0 }

    /**
     * Distinct species you have recorded in this family.
     *
     * Species only: a record kept at genus — "some *Eupithecia*" — is a real record and an
     * honest one, but it is not one of the 310, and counting it as one would make the
     * numerator a different kind of thing from the denominator. Indeterminate leaves carry
     * negative ids and are excluded for the same reason.
     */
    fun seenIn(taxonomy: Taxonomy, records: List<Record>, familyId: Int): Int =
        records
            .asSequence()
            .map { it.taxonId }
            .filter { it > 0 }
            .distinct()
            .filter { taxonomy.nodes[it]?.isLeaf == true }
            .count { taxonomy.isAncestorOrSelf(familyId, it) }

    /**
     * The line for one taxon, or null when it has no family or nothing to count against.
     *
     * `danishTotals` is keyed by scientific name because that is what the Red List publishes —
     * it has no GBIF ids, and matching two authorities on names is exactly what `redlist.py`
     * does once, offline, rather than something to redo per frame.
     */
    fun progressFor(
        taxonomy: Taxonomy,
        records: List<Record>,
        taxonId: Int,
        danishTotals: Map<String, Int> = emptyMap(),
    ): Progress? {
        val family = familyOf(taxonomy, taxonId) ?: return null
        val known = knownToApp(taxonomy, family.taxonId)
        val danish = danishTotals[family.scientificName] ?: 0
        // `max`, not the Danish figure alone: the Red List does not assess every group, and it
        // lists 19 Plantaginaceae where the model recognises 31. A denominator smaller than the
        // numerator is worse than a denominator that undersells the country.
        val total = maxOf(known, danish)
        if (total <= 0) return null
        return Progress(
            familyId = family.taxonId,
            scientificName = family.scientificName,
            vernacularEn = family.vernacularEn,
            seen = seenIn(taxonomy, records, family.taxonId),
            total = total,
            source = if (danish >= known && danish > 0) Source.DENMARK else Source.APP,
        )
    }

    /** One species in a family, and whether it is on your list. */
    data class Member(
        val taxonId: Int,
        val scientificName: String,
        val vernacularEn: String?,
        val seen: Boolean,
    )

    /**
     * Every species of a family the app can name, the ones you have first.
     *
     * "1 of 11 Katydids" is a number you can read and not a thing you can act on. The list
     * behind it is: it says which bush-cricket you already have and which ten are still out
     * there, which is the difference between a score and a to-do list.
     *
     * Species only, matching [seenIn] — a record kept at genus is real and honest but is not
     * one of the eleven, and the synthetic `sp.` leaves (negative ids) are not species anybody
     * can go and find.
     *
     * Found first, then alphabetically by the name actually shown. Found-first because the
     * point of opening the row is usually "what have I got", and a stable order because a list
     * that reshuffles when you add to it is a list you cannot keep your place in.
     */
    fun membersOf(
        taxonomy: Taxonomy,
        records: List<Record>,
        familyId: Int,
    ): List<Member> {
        val seen = records
            .asSequence()
            .map { it.taxonId }
            .filter { it > 0 }
            .toSet()

        return taxonomy.subtreeLeafIndices(familyId)
            .map { taxonomy.leafId(it) }
            .filter { it > 0 }
            .mapNotNull { taxonomy.nodes[it] }
            .map {
                Member(
                    taxonId = it.taxonId,
                    scientificName = it.scientificName,
                    vernacularEn = it.vernacularEn,
                    seen = it.taxonId in seen,
                )
            }
            .sortedWith(
                compareByDescending<Member> { it.seen }
                    .thenBy { it.vernacularEn ?: it.scientificName }
            )
    }

    /**
     * How many species of this family Denmark has that the app cannot name.
     *
     * The two denominators in [Progress] are different kinds of thing, and opening a row is
     * where that stops being a footnote. A Red List total is a *count* with no species list
     * behind it, so a family showing "1 of 8 Melittidae in Denmark" can name however many the
     * model was trained on and must say plainly that the rest exist and it does not know them.
     * Listing seven and calling it eight would be the more comfortable lie.
     */
    fun unnamed(progress: Progress, members: List<Member>): Int =
        (progress.total - members.size).coerceAtLeast(0)

    /**
     * Every family you have a species from, fullest first.
     *
     * Fullest rather than largest: a life list is a thing you fill in, and the row worth
     * showing at the top is the one you are closest to finishing.
     */
    fun seenFamilies(
        taxonomy: Taxonomy,
        records: List<Record>,
        danishTotals: Map<String, Int> = emptyMap(),
    ): List<Progress> =
        records
            .asSequence()
            .map { it.taxonId }
            .distinct()
            .mapNotNull { familyOf(taxonomy, it) }
            .map { it.taxonId }
            .distinct()
            .mapNotNull { progressFor(taxonomy, records, it, danishTotals) }
            .sortedWith(compareByDescending<Progress> { it.fraction }.thenBy { it.scientificName })
            .toList()
}
