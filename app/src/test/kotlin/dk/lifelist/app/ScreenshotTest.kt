package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.ui.text.AnnotatedString
import dk.lifelist.core.Answer
import dk.lifelist.core.AnswerKind
import dk.lifelist.core.Candidate
import dk.lifelist.core.Determiner
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.RollupResult
import dk.lifelist.core.Taxon
import dk.lifelist.core.Taxonomy
import org.junit.Rule
import org.junit.Test

/**
 * Screens, rendered.
 *
 * Every screen in this app has shipped at least one bug that a single glance would have caught:
 * the camera drawn under the system buttons, a slider that did nothing, a species name printed
 * twice, a status line truncated to 28 characters in a box 96dp wide. The cause was always the
 * same — nothing in `app/` had ever been *run* before it reached the phone, because running it
 * meant a device, and the device was an hour away down a CI pipeline.
 *
 * Paparazzi renders composables through layoutlib on the JVM, in about a second. That closes
 * the loop: a layout that overlaps, clips or overflows now fails here rather than being noticed
 * in a field in Denmark. `./gradlew :app:recordPaparazzi` writes the images.
 *
 * These are deliberately *not* pixel-comparison tests in CI. Golden images across layoutlib
 * versions are a maintenance tax, and the value here is being able to look at the thing.
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
    )

    // -- a taxonomy small enough to reason about --------------------------------

    private val taxonomy = Taxonomy(
        listOf(
            Taxon(0, null, "root", "Life"),
            Taxon(216, 0, "class", "Insecta", vernacularEn = "Insects"),
            Taxon(500, 216, "order", "Orthoptera", vernacularEn = "Grasshoppers and crickets"),
            Taxon(600, 500, "family", "Tettigoniidae", vernacularEn = "Bush-crickets"),
            Taxon(700, 600, "genus", "Leptophyes"),
            Taxon(800, 600, "genus", "Tettigonia"),
            Taxon(
                1688020, 700, "species", "Leptophyes punctatissima",
                vernacularEn = "Speckled bush-cricket", leafIndex = 0,
            ),
            Taxon(
                1692898, 800, "species", "Tettigonia viridissima",
                vernacularEn = "Great green bush-cricket", leafIndex = 1,
            ),
            Taxon(212, 0, "class", "Aves", vernacularEn = "Birds"),
            Taxon(
                9761484, 212, "species", "Anas platyrhynchos",
                vernacularEn = "Mallard", leafIndex = 2,
            ),
        )
    )

    private val candidates = listOf(
        Candidate(1688020, 0, 0.41f),
        Candidate(1692898, 1, 0.38f),
        Candidate(9761484, 2, 0.21f),
    )

    private fun answer(taxonId: Int, rank: String, probability: Float) =
        Presentation.present(
            taxonomy,
            RollupResult(taxonId, rank, probability, candidates, threshold = 0.70f),
        )

    private fun record(id: String, taxonId: Int, at: Long, by: Determiner = Determiner.MODEL) =
        Record(
            id = id, taxonId = taxonId, observedAt = at, photoPaths = emptyList(),
            threshold = 0.70f, modelVersion = "2026-08-18-full", determinedBy = by,
            confidence = 0.91f, latitude = 55.676, longitude = 12.568,
        )

    /** A stand-in photograph: a gradient, so cropping and scaling are visible. */
    private fun photo(from: Int, to: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, 600f, 800f,
            Paint().apply {
                shader = LinearGradient(0f, 0f, 600f, 800f, from, to, Shader.TileMode.CLAMP)
            },
        )
        return bitmap
    }

    // Lazy, not eager: layoutlib's graphics stack is stood up by the Paparazzi rule, and a
    // field initialiser runs before any rule, so `Bitmap.createBitmap` at construction time
    // returns null and every test dies on the same NullPointerException.
    private val yours by lazy { photo(Color.rgb(84, 122, 74), Color.rgb(26, 44, 24)) }
    private val reference by lazy { photo(Color.rgb(150, 138, 96), Color.rgb(60, 52, 30)) }

    private val article = Wikipedia.Article(
        title = "Speckled bush-cricket",
        extract = "The speckled bush-cricket (Leptophyes punctatissima) is a flightless " +
            "species of bush-cricket. Adults are green, densely covered with tiny dark " +
            "speckles, with a pale dorsal stripe and antennae far longer than the body.",
        url = "https://en.wikipedia.org/wiki/Speckled_bush-cricket",
    )

    private fun choice(taxonId: Int, percent: String?, fraction: Float?): Choice {
        val node = taxonomy.node(taxonId)
        return Choice(
            taxonId = taxonId,
            name = AnnotatedString(node.scientificName),
            vernacular = node.vernacularEn,
            percent = percent,
            fraction = fraction,
            photo = if (taxonId == 1688020) yours else reference,
        )
    }

    // -- home -------------------------------------------------------------------

    @Test
    fun `home with a list`() {
        val records = listOf(
            record("a", 1688020, 1_755_000_000_000),
            record("b", 9761484, 1_754_000_000_000),
            record("c", 600, 1_753_000_000_000),
            record("d", 1692898, 1_752_000_000_000),
        )
        paparazzi.snapshot {
            LifeListTheme {
                HomeScreen(taxonomy, records, onOpenRecord = {}, onOpenGroup = {})
            }
        }
    }

    @Test
    fun `home with nothing on it yet`() {
        // The first thing anyone sees. Ten rows of "Nothing here yet" is not a welcome.
        paparazzi.snapshot {
            LifeListTheme { HomeScreen(taxonomy, emptyList(), onOpenRecord = {}, onOpenGroup = {}) }
        }
    }

    @Test
    fun `home survives a record this taxonomy has never heard of`() {
        // The v0.7.1 crash, rendered. The home screen was handed the demo taxonomy and asked
        // to look up real saved taxa in it; `Taxonomy.node` throws, so the first frame died and
        // every launch after it died the same way. This test fails by throwing, which is the
        // whole point — a screen that cannot compose is a screen nobody can get past.
        val records = listOf(
            record("known", 1688020, 1_755_000_000_000),
            record("orphan", 999_999_999, 1_754_000_000_000),
        )
        paparazzi.snapshot {
            LifeListTheme {
                HomeScreen(taxonomy, records, onOpenRecord = {}, onOpenGroup = {})
            }
        }
    }

    @Test
    fun `a group survives one too`() {
        paparazzi.snapshot {
            LifeListTheme {
                GroupScreen(
                    taxonomy,
                    "Other",
                    listOf(record("orphan", 999_999_999, 1_754_000_000_000)),
                    onOpenRecord = {},
                )
            }
        }
    }

    // -- result -----------------------------------------------------------------

    @Test
    fun `a confident species, new to the list`() {
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(1688020, "species", 0.97f),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = ReferencePhotos.Credit("magnedylmer", "CC-BY-NC"),
                    article = article,
                    choices = emptyList(),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = "Model 2026-08-18-full · 2294 taxa",
                )
            }
        }
    }

    @Test
    fun `a hedged answer asks which one`() {
        // The picker has to be on screen without scrolling, or the question is not asked.
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(600, "family", 0.88f),
                    isFirst = false,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = ReferencePhotos.Credit("mikkel65", "CC-BY-NC"),
                    article = article,
                    choices = listOf(
                        choice(1688020, "41%", 0.41f),
                        choice(1692898, "38%", 0.38f),
                    ),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = "Model 2026-08-18-full · 2294 taxa",
                )
            }
        }
    }

    @Test
    fun `once you pick one it is yours`() {
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(600, "family", 0.88f),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = null,
                    article = article,
                    choices = listOf(
                        choice(1688020, "41%", 0.41f),
                        choice(1692898, "38%", 0.38f),
                    ),
                    picked = choice(1688020, "41%", 0.41f),
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = null,
                )
            }
        }
    }

    @Test
    fun `nothing defensible to say`() {
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(0, "root", 1.0f),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = null,
                    referenceCredit = null,
                    article = null,
                    choices = emptyList(),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = "Model 2026-08-18-full · 2294 taxa",
                )
            }
        }
    }

    @Test
    fun `a long common name does not break the headline`() {
        // Real names in this taxonomy run to "Common Speckled Bush-cricket" and longer; GBIF
        // has vernaculars over forty characters. A headline that clips is a headline that lies.
        val long = Taxonomy(
            taxonomy.nodes.values.map {
                if (it.taxonId == 1688020) {
                    it.copy(vernacularEn = "Southern oak bush-cricket of the eastern isles")
                } else it
            }
        )
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = Presentation.present(
                        long,
                        RollupResult(1688020, "species", 0.97f, candidates, 0.70f),
                    ),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = ReferencePhotos.Credit("someone", "CC-BY"),
                    article = article,
                    choices = emptyList(),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = true,
                    modelNote = null,
                )
            }
        }
    }

    @Test
    fun `a lone contender is asked about, not ignored`() {
        // Yponomeuta at 71% held exactly one species below it and so was offered no question
        // at all, while a family two taps earlier offered three. The screen has to read as a
        // question with one card in it, not as a stray card.
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(700, "genus", 0.71f),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = null,
                    article = article,
                    choices = listOf(choice(1688020, "69%", 0.69f)),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = null,
                )
            }
        }
    }

    @Test
    fun `several photographs of the same individual`() {
        // Adding a second photo used to change nothing visible on this screen.
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(1688020, "species", 0.97f),
                    isFirst = false,
                    photos = listOf(yours, reference, yours),
                    reference = reference,
                    referenceCredit = ReferencePhotos.Credit("magnedylmer", "CC-BY-NC"),
                    article = article,
                    choices = emptyList(),
                    picked = null,
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = "Model 2026-08-18-full · 3 photos fused",
                )
            }
        }
    }

    // -- a group, opened --------------------------------------------------------

    @Test
    fun `a group you can actually open`() {
        val records = listOf(
            record("a", 1688020, 1_755_000_000_000).copy(place = "Vanl\u00f8se, Copenhagen"),
            record("b", 600, 1_754_000_000_000),
            record("c", 1692898, 1_752_000_000_000).copy(place = "Amager F\u00e6lled"),
        )
        paparazzi.snapshot {
            LifeListTheme { GroupScreen(taxonomy, "Insects", records, onOpenRecord = {}) }
        }
    }

    @Test
    fun `a name the user found themselves has no percentage to show`() {
        // The model returned a confident species and it was the wrong moth — reported twice
        // from real use. There is no model number behind a name someone searched out, and
        // inventing one would be the exact overclaim this app exists to avoid.
        paparazzi.snapshot {
            LifeListTheme {
                ResultScreen(
                    answer = answer(1688020, "species", 0.94f),
                    isFirst = true,
                    photos = listOf(yours),
                    reference = reference,
                    referenceCredit = null,
                    article = article,
                    choices = emptyList(),
                    picked = choice(1692898, null, null),
                    onPick = {}, onKeep = {}, onAddPhoto = {}, onRetake = {}, onBack = {},
                    onOpenPhoto = { _, _ -> }, onOpenTaxon = {}, onSearchAll = {}, onKeepBroader = {},
                    kept = false,
                    modelNote = null,
                )
            }
        }
    }

    // -- the wait ---------------------------------------------------------------

    @Test
    fun `looking`() {
        paparazzi.snapshot {
            LifeListTheme { ThinkingScreen(yours, "On this phone. Nothing leaves it.") }
        }
    }
}
