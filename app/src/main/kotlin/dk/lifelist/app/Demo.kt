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
            // A second genus under Anatidae, so a duck can retreat to *family* rather than
            // straight to nothing. With Anas the only genus, Anatidae and Anas hold identical
            // mass and the family step is unreachable.
            Taxon(8996942, 2986, "genus", "Cygnus"),
            Taxon(2498343, 8996942, "species", "Cygnus olor", vernacularEn = "Mute Swan", leafIndex = 8),
        )
    )

    data class Case(val label: String, val probabilities: FloatArray)

    /**
     * Chosen so the threshold slider visibly *does something* on every case.
     *
     * The first attempt put 93% of the mass inside Anas, which meant the answer stayed Anas
     * across the whole 0.50–0.95 range and the slider looked broken. It was not broken; it had
     * nothing to say. A demo that cannot demonstrate the one behaviour worth demonstrating is
     * a bug in the demo.
     *
     * Retreat points, so this stays checkable: case 2 leaves Anas above 0.88, case 3 leaves
     * Carabus sp. above 0.72 and Carabus above 0.95, case 1 holds species to 0.94.
     */
    val cases = listOf(
        // leaf order: urticae, io, Carabus sp., granulatus, nemoralis, platyrhynchos,
        // crecca, acuta, olor. Each vector sums to exactly 1.0 — Rollup rejects anything
        // else, and a demo vector that does not is a crash on the shutter button.
        Case("Confident species", floatArrayOf(0.94f, 0.03f, 0.005f, 0.005f, 0.005f, 0.01f, 0.003f, 0.001f, 0.001f)),
        Case("Genus-level answer", floatArrayOf(0.04f, 0.02f, 0.01f, 0.005f, 0.005f, 0.40f, 0.32f, 0.14f, 0.06f)),
        Case("Undetermined Carabus", floatArrayOf(0.03f, 0.02f, 0.72f, 0.13f, 0.10f, 0.0f, 0.0f, 0.0f, 0.0f)),
        Case("Nothing defensible", floatArrayOf(0.16f, 0.12f, 0.10f, 0.09f, 0.08f, 0.18f, 0.12f, 0.09f, 0.06f)),
    )
}
