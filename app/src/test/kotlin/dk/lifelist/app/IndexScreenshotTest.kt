package dk.lifelist.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dk.lifelist.core.Checklist
import dk.lifelist.core.ChecklistFamily
import dk.lifelist.core.ChecklistSpecies
import dk.lifelist.core.Determiner
import dk.lifelist.core.Index
import dk.lifelist.core.Record
import org.junit.Rule
import org.junit.Test

/**
 * The index, rendered against real numbers.
 *
 * Every count here is read out of `shared/model/checklist.json` rather than invented: Denmark
 * has 705 birds in 90 families and the app can name 238 of them; 74 of the 705 are ducks and
 * the camera can name 41. A mock with tidy numbers would have hidden the thing this screen has
 * to survive, which is that most rows are species no model in the app can identify.
 */
class IndexScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.0)

    private val aves = listOf(1, 44, 212)

    private fun family(id: Int, latin: String, english: String?, species: Int, model: Int) =
        ChecklistFamily(id, latin, english, aves + listOf(id), species, model)

    private fun sp(id: Int, latin: String, english: String?, records: Int, model: Boolean) =
        ChecklistSpecies(id, latin, english, 2986, records, model)

    /** The nine biggest bird families in Denmark, exactly as the checklist has them. */
    private val birdFamilies = listOf(
        family(2986, "Anatidae", "Ducks, Geese, And Swans", 74, 41),
        family(9331, "Phasianidae", "Pheasants, Grouse, And Allies", 53, 4),
        family(5282, "Scolopacidae", "Sandpipers And Allies", 43, 22),
        family(9316, "Laridae", "Gulls, Terns, and Skimmers", 38, 16),
        family(9322, "Muscicapidae", "Old World Flycatchers And Chats", 32, 10),
        family(2877, "Accipitridae", "Hawks, Eagles, And Kites", 31, 11),
        family(5242, "Fringillidae", "Finches, Euphonias, and Allies", 29, 12),
        family(5285, "Sylviidae", "Old World warblers", 18, 4),
        family(9608, "Emberizidae", "Buntings", 17, 3),
    )

    /** Anatidae, commonest first, including six the camera cannot settle. */
    private val ducks = listOf(
        sp(9761484, "Anas platyrhynchos", "Mallard", 779131, true),
        sp(2498036, "Anser anser", "Greylag Goose", 778323, true),
        sp(2498343, "Cygnus olor", "Mute Swan", 681258, true),
        sp(2498261, "Aythya fuligula", "Tufted Duck", 410101, true),
        sp(2498009, "Tadorna tadorna", "Common Shelduck", 403863, true),
        sp(8214667, "Anas crecca", "Eurasian Teal", 384522, true),
        sp(8000602, "Mareca penelope", "Eurasian Wigeon", 367937, true),
        sp(2498352, "Somateria mollissima", "Common Eider", 348040, true),
        sp(2498347, "Cygnus cygnus", "Whooper Swan", 334862, true),
        sp(2498326, "Bucephala clangula", "Common Goldeneye", 324364, true),
        sp(2498250, "Melanitta americana", "Black Scoter", 2814, false),
        sp(2498167, "Anser caerulescens", "Snow Goose", 2572, false),
        sp(2498259, "Aythya nyroca", "Ferruginous Duck", 2522, false),
        sp(2498246, "Melanitta perspicillata", "Surf Scoter", 2427, false),
        sp(2498305, "Oxyura jamaicensis", "Ruddy Duck", 1705, false),
        sp(2498125, "Anas carolinensis", "Green-winged Teal", 1469, false),
    )

    private fun record(taxonId: Int) = Record(
        id = "r$taxonId", taxonId = taxonId, observedAt = 1_755_000_000_000,
        photoPaths = emptyList(), threshold = 0.70f, modelVersion = "test",
        determinedBy = Determiner.MODEL, confidence = 0.9f,
    )

    /** Four ducks and a gull, so the meters have something in them. */
    private val mine = listOf(
        record(9761484), record(2498343), record(2498036), record(2498261),
    )

    private val checklist = Checklist(
        families = birdFamilies.associateBy { it.taxonId },
        species = ducks.associateBy { it.taxonId },
    )

    /** A stand-in photograph: a gradient, so cropping and scaling are visible. */
    private fun photo(from: Int, to: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, 600f, 600f,
            Paint().apply {
                shader = LinearGradient(0f, 0f, 600f, 600f, from, to, Shader.TileMode.CLAMP)
            },
        )
        return bitmap
    }

    private val greens = listOf(
        Color.rgb(96, 110, 78) to Color.rgb(38, 44, 30),
        Color.rgb(120, 104, 74) to Color.rgb(48, 42, 30),
        Color.rgb(86, 98, 112) to Color.rgb(32, 38, 46),
    )

    @Test
    fun `the birds of Denmark, ninety families deep`() {
        // 238 of 705 is what the app can name, not what he has found — the heading here is
        // his four, against the country. The distance between those two numbers is the screen.
        paparazzi.snapshot {
            LifeListTheme {
                IndexScreen(
                    group = "Birds",
                    lines = Index.families(checklist, "Birds", mine),
                    // Denmark's real birds, not this fixture's nine families — which is the
                    // point of the parameter: the headline is a fact about the country and
                    // must not move when the list below it is cut down.
                    standing = Index.GroupLine("Birds", found = 4, total = 705, families = 90),
                    onOpenFamily = {},
                    heroFor = { id -> photo(greens[id % 3].first, greens[id % 3].second) },
                )
            }
        }
    }

    @Test
    fun `seventy-four ducks, four of them yours`() {
        paparazzi.snapshot {
            LifeListTheme {
                FamilyRoster(
                    family = "Ducks, Geese, And Swans",
                    latin = "Anatidae",
                    members = Index.members(checklist, 2986, mine),
                    total = 74,
                    identifiable = 41,
                    onOpenTaxon = {},
                    thumbnailFor = { id ->
                        if (id % 5 == 0) null
                        else photo(greens[id % 3].first, greens[id % 3].second)
                    },
                )
            }
        }
    }
}
