package dk.lifelist.app

import dk.lifelist.core.Taxon
import dk.lifelist.core.Taxonomy

/**
 * A hand-built taxonomy and a few probability vectors, standing in for the model.
 *
 * Stage 6 has not exported anything yet, so there is nothing to run on device. What this
 * screen must get right today is the *rollup's* behaviour and how it reads — and that is
 * real: `dk.lifelist.core.Rollup` and `Presentation` do the work here exactly as they will
 * with a real head behind them. Only the numbers are stand-ins.
 */
object Demo {

    val taxonomy: Taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta"),
            Taxon(7017, 216, "family", "Nymphalidae"),
            Taxon(1898286, 7017, "genus", "Aglais"),
            Taxon(1898287, 1898286, "species", "Aglais urticae", vernacularEn = "Small Tortoiseshell", leafIndex = 0),
            Taxon(1898288, 1898286, "species", "Aglais io", vernacularEn = "European Peacock", leafIndex = 1),
            Taxon(5602, 216, "family", "Carabidae"),
            Taxon(1036775, 5602, "genus", "Carabus"),
            Taxon(-1036775, 1036775, "species", "Carabus sp.", leafIndex = 2),
            Taxon(1036776, 1036775, "species", "Carabus granulatus", leafIndex = 3),
            Taxon(1036777, 1036775, "species", "Carabus nemoralis", leafIndex = 4),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(2498118, 2986, "genus", "Anas"),
            Taxon(2498036, 2498118, "species", "Anas platyrhynchos", vernacularEn = "Mallard", leafIndex = 5),
            Taxon(8214667, 2498118, "species", "Anas crecca", vernacularEn = "Eurasian Teal", leafIndex = 6),
            Taxon(2498101, 2498118, "species", "Anas acuta", vernacularEn = "Northern Pintail", leafIndex = 7),
        )
    )

    data class Case(val label: String, val probabilities: FloatArray)

    val cases = listOf(
        Case("Confident species", floatArrayOf(0.94f, 0.03f, 0.005f, 0.005f, 0.005f, 0.01f, 0.003f, 0.002f)),
        Case("Genus-level answer", floatArrayOf(0.04f, 0.02f, 0.005f, 0.003f, 0.002f, 0.41f, 0.33f, 0.19f)),
        Case("Undetermined Carabus", floatArrayOf(0.03f, 0.02f, 0.72f, 0.13f, 0.10f, 0.0f, 0.0f, 0.0f)),
        Case("Nothing defensible", floatArrayOf(0.18f, 0.13f, 0.09f, 0.16f, 0.08f, 0.20f, 0.09f, 0.07f)),
    )
}
