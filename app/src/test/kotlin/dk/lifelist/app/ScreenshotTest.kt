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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
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

    private fun record(
        id: String,
        taxonId: Int,
        at: Long,
        by: Determiner = Determiner.MODEL,
        clip: String? = null,
    ) = Record(
        id = id, taxonId = taxonId, observedAt = at, photoPaths = emptyList(),
        threshold = 0.70f, modelVersion = "2026-08-18-full", determinedBy = by,
        confidence = 0.91f, latitude = 55.676, longitude = 12.568, clipPath = clip,
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
                HomeScreen(
                    taxonomy, records, onOpenRecord = {}, onOpenGroup = {},
                    referencePhotoFor = { photo(Color.rgb(104, 92, 70), Color.rgb(44, 38, 28)) },
                )
            }
        }
    }

    @Test
    fun `a record you heard wears the species picture, marked as not yours`() {
        // The blank tiles in his list: a bird identified by sound has no photograph, and the
        // card for one was an empty square with a name under it. It now shows the species'
        // own picture — with a waveform on it, because a reference photograph passed off as
        // yours would quietly turn a life list into a field guide.
        val records = listOf(
            record("heard", 1688020, 1_755_000_000_000, clip = "/clips/1.wav"),
            record("seen", 9761484, 1_754_000_000_000),
        )
        paparazzi.snapshot {
            LifeListTheme {
                HomeScreen(
                    taxonomy, records, onOpenRecord = {}, onOpenGroup = {},
                    referencePhotoFor = { photo(Color.rgb(120, 104, 74), Color.rgb(48, 42, 30)) },
                )
            }
        }
    }

    @Test
    fun `the record page of a bird you only heard`() {
        // No photograph of yours, so the species' own — and the photographer's name under it,
        // which is both what the licence asks for and the thing that makes it unmistakably
        // not yours. On the tiles there is no room for a sentence, so they wear a waveform
        // instead; here there is room, so it says so in words.
        paparazzi.snapshot {
            LifeListTheme {
                // The sheet's own ground, which the ModalBottomSheet supplies in the app.
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Details(
                    taxonomy = taxonomy,
                    record = record("heard", 1688020, 1_755_000_000_000, clip = "/clips/1.wav"),
                    article = null,
                    suggestion = null,
                    onUseSuggestion = {}, onDismissSuggestion = {},
                    onOpenPhoto = {}, onAddPhoto = {}, onSettle = {}, onCorrect = {},
                    onBroaden = {}, onEdit = {},
                    referencePhotoFor = { photo(Color.rgb(120, 104, 74), Color.rgb(48, 42, 30)) },
                    referenceCreditFor = {
                        ReferencePhotos.Credit("Gilles San Martin", "CC BY-SA 4.0")
                    },
                )
                }
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

    // -- listening --------------------------------------------------------------
    //
    // The audio path cannot be run off a phone, so what *can* be checked here is the one thing
    // that has bitten every screen in this app: whether the layout survives its own content.
    // A row carrying "against Willow Warbler, Wood Warbler" next to a confidence ring on a
    // Pixel 5 is exactly the kind of line that gets truncated in a field in Denmark.

    /**
     * A spectrogram filled by the real thing: synthetic birdsong through the real
     * [dk.lifelist.core.Spectrograph] and the real colour scale. A hand-painted gradient here
     * would render beautifully and prove nothing — the question this snapshot answers is
     * whether a song is legible at 136dp on a phone, and only real columns can answer it.
     */
    private fun sungInto(state: SpectrogramState): SpectrogramState {
        val rate = 32_000
        val graph = dk.lifelist.core.Spectrograph(sampleRate = rate)
        val random = java.util.Random(4)

        // A phase accumulator, not `sin(2 * PI * f(t) * t)`. The second is the obvious way to
        // write a falling note and is not one: the instantaneous frequency of it is
        // `f + t·df/dt`, which for a note falling over half a second is several kilohertz below
        // zero within a breath, and what it draws is the aliasing rather than the bird. The
        // first version of this fixture did exactly that and rendered a convincing picture of
        // nothing. Caught by reading the snapshot against where the mel axis says 8 kHz is.
        var phase = 0.0
        val samples = FloatArray(rate * 10) { i ->
            val t = i.toDouble() / rate
            var v = random.nextGaussian() * 0.0006          // the afternoon
            val phrase = t % 2.4

            // A chaffinch-ish cascade: falling, twice a bar, with a second harmonic.
            val hz = if (phrase < 0.55) 5200 - 2600 * (phrase / 0.55) else 0.0
            phase += 2 * Math.PI * hz / rate
            if (hz > 0) {
                val envelope = kotlin.math.sin(Math.PI * (phrase / 0.55))
                v += 0.30 * kotlin.math.sin(phase) * envelope
                v += 0.08 * kotlin.math.sin(2 * phase) * envelope
            }

            // A single high seep, steady, where a goldcrest lives.
            if (phrase > 1.2 && phrase < 1.45) {
                v += 0.10 * kotlin.math.sin(2 * Math.PI * 8100 * t)
            }

            // A car going past: broadband, low, and nothing to do with any bird.
            if (t > 6.0 && t < 8.5) {
                v += random.nextGaussian() * 0.02 * kotlin.math.sin(Math.PI * (t - 6.0) / 2.5)
            }
            v.toFloat()
        }
        // Offered and then drained: in the app the columns are paced out on the frame clock,
        // and a snapshot has no frames.
        state.offer(graph.add(samples))
        state.drain()
        return state
    }

    private fun heard(
        taxonId: Int,
        name: String,
        rank: String,
        confidence: Float?,
        detected: Float,
        at: Float,
        against: List<String> = emptyList(),
        saved: Boolean = false,
    ) = Heard(
        taxonId, name, rank, confidence,
        probability = confidence ?: detected,
        detected = detected,
        atSeconds = at,
        alsoConsidered = against,
        threshold = 0.70f, clipPath = "/clips/$taxonId.wav", saved = saved,
    )

    @Test
    fun `a listening session with three birds`() {
        paparazzi.snapshot {
            LifeListTheme {
                ListenScreen(
                    spectrogram = sungInto(SpectrogramState()),
                    thumbnailFor = { photo(Color.rgb(96, 84, 66), Color.rgb(38, 32, 24)) },
                    listening = true,
                    heard = listOf(
                        heard(2490719, "Common Blackbird", "species", 0.94f, 0.94f, 12f),
                        heard(
                            2493047, "Phylloscopus", "genus", 0.81f, 0.48f, 35f,
                            against = listOf("Willow Warbler", "Wood Warbler"),
                        ),
                        heard(1688020, "Speckled bush-cricket", "species", 0.77f, 0.77f, 51f, saved = true),
                    ),
                    elapsedSeconds = 55f,
                    permission = true,
                    modelReady = true,
                    note = null,
                    onStart = {}, onStop = {}, onSave = {},
                )
            }
        }
    }

    @Test
    fun `a detection the app will not commit to`() {
        paparazzi.snapshot {
            LifeListTheme {
                ListenScreen(
                    listening = true,
                    heard = listOf(
                        heard(2490719, "Common Blackbird", "unknown", null, 0.31f, 8f),
                        heard(2482443, "Eurasian Magpie", "unknown", null, 0.66f, 31f),
                        heard(9515886, "Northern Raven", "unknown", null, 0.12f, 44f),
                    ),
                    spectrogram = sungInto(SpectrogramState()),
                    elapsedSeconds = 20f,
                    permission = true,
                    modelReady = true,
                    note = null,
                    onStart = {}, onStop = {}, onSave = {},
                )
            }
        }
    }

    @Test
    fun `listening before the microphone has been allowed`() {
        paparazzi.snapshot {
            LifeListTheme {
                ListenScreen(
                    spectrogram = SpectrogramState(),
                    listening = false,
                    heard = emptyList(),
                    elapsedSeconds = 0f,
                    permission = false,
                    modelReady = true,
                    note = null,
                    onStart = {}, onStop = {}, onSave = {},
                )
            }
        }
    }

    // -- what is behind a family's number ----------------------------------------

    @Test
    fun `a family opened to show what is found and what is not`() {
        val progress = dk.lifelist.core.Families.Progress(
            familyId = 600,
            scientificName = "Tettigoniidae",
            vernacularEn = "Katydids",
            seen = 1,
            total = 11,
            source = dk.lifelist.core.Families.Source.DENMARK,
        )
        val members = listOf(
            dk.lifelist.core.Families.Member(
                1688020, "Leptophyes punctatissima", "Speckled bush-cricket", seen = true
            ),
            dk.lifelist.core.Families.Member(
                1692898, "Tettigonia viridissima", "Great green bush-cricket", seen = false
            ),
            // No English name anywhere — 229 species are in this position, so the row has to
            // read as a scientific name rather than as one that failed to load.
            dk.lifelist.core.Families.Member(700, "Conocephalus fuscus", null, seen = false),
        )

        paparazzi.snapshot {
            LifeListTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(20.dp)
                ) {
                    FamilyProgressRow(
                        progress = progress,
                        compact = true,
                        members = members,
                        expanded = true,
                        onToggle = {},
                        // Two of the three have a photograph and one does not, because a row
                        // with no picture has to line up with the rows that have one.
                        thumbnailFor = { taxonId ->
                            if (taxonId == 700) null
                            else photo(Color.rgb(108, 126, 78), Color.rgb(40, 52, 30))
                        },
                    )
                }
            }
        }
    }
}
