package dk.lifelist.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Determiner
import dk.lifelist.core.LifeList
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Rollup
import dk.lifelist.core.RollupResult
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is the default from targetSdk 35 whether asked for or not, so the
        // choice is only whether the app respects the insets or draws under the buttons.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LifeListTheme { App() } }
    }
}

/**
 * One surface.
 *
 * The app used to have two tabs and a camera you launched into, and it did not feel like one
 * thing. The diagnosis was structural rather than visual: a classifier with a list filed behind
 * a tab is a tool you use, and a list you add to with a camera is a collection you keep. Only
 * the second one is worth opening twice.
 *
 * So: home is your life list. Capture is a full-screen moment you enter on purpose and leave
 * with an X. The result slides in over it, and dismissing it puts you back on a list that just
 * grew. There is no tab bar, because there is nowhere else to be.
 */
private enum class Screen { HOME, CAPTURE, THINKING, RESULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val references = remember { ReferencePhotos(context) }
    val wikipedia = remember { Wikipedia(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Coarse location, asked for once, at the moment it would first be used.
    val askWhere = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var thresholdSheet by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    var readingAbout by remember { mutableStateOf<Int?>(null) }
    var openRecord by remember { mutableStateOf<Record?>(null) }

    var threshold by remember { mutableFloatStateOf(0.70f) }
    var caseIndex by remember { mutableIntStateOf(0) }

    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var leafProbabilities by remember { mutableStateOf<FloatArray?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Choice?>(null) }
    var records by remember { mutableStateOf(store.load()) }

    // Loading a 335 MB session takes a moment, so it happens off the main thread and the
    // absence of a model is a state rather than a crash.
    val loaded by produceState<Loaded?>(initialValue = null) {
        value = runCatching {
            val taxonomy = TaxonomyAssets.loadTaxonomy(context)
            val meta = TaxonomyAssets.loadMeta(context)
            Loaded(Identifier.openOrReport(context, taxonomy, meta.temperature), meta)
        }.getOrElse {
            Loaded(Identifier.Companion.Outcome.Failed("assets: ${it.message}"), null)
        }
    }
    val identifier = (loaded?.outcome as? Identifier.Companion.Outcome.Ready)?.identifier
    val taxonomy = identifier?.taxonomy ?: Demo.taxonomy

    val rollup: RollupResult = if (identifier != null && leafProbabilities != null) {
        Rollup.rollup(taxonomy, leafProbabilities!!, threshold)
    } else {
        Rollup.rollup(Demo.taxonomy, Demo.cases[caseIndex].probabilities, threshold)
    }
    val answer = Presentation.present(
        if (identifier != null && leafProbabilities != null) taxonomy else Demo.taxonomy,
        rollup,
    )

    // The contenders a hedge can hand back to the user. Built here because it needs the
    // taxonomy and the reference photos, neither of which the result screen may know about.
    val choices = remember(rollup, records) {
        LifeList.choices(
            if (identifier != null && leafProbabilities != null) taxonomy else Demo.taxonomy,
            rollup,
        ).map { candidate ->
            val node = (if (identifier != null && leafProbabilities != null) taxonomy else Demo.taxonomy)
                .node(candidate.taxonId)
            Choice(
                taxonId = candidate.taxonId,
                name = Presentation.styleName(node.scientificName, node.rank).annotated(),
                vernacular = node.vernacularEn,
                percent = Presentation.confidence(candidate.probability).percent,
                fraction = Presentation.confidence(candidate.probability).barFraction,
                photo = references.photo(candidate.taxonId),
            )
        }
    }

    fun identify(bitmaps: List<Bitmap>) {
        photos = bitmaps
        leafProbabilities = null
        failure = null
        kept = false
        picked = null
        screen = Screen.THINKING
        if (bitmaps.isNotEmpty() && identifier != null) {
            thread {
                runCatching { identifier.identify(bitmaps) }.fold(
                    onSuccess = { leafProbabilities = it; screen = Screen.RESULT },
                    onFailure = {
                        failure = "${it::class.simpleName}: ${it.message}"
                        screen = Screen.RESULT
                    },
                )
            }
        } else {
            // No model in this build: still show the demo answer, but not instantly, or the
            // shutter appears not to have done anything at all.
            thread { Thread.sleep(700); screen = Screen.RESULT }
        }
    }

    fun startOver() {
        screen = Screen.HOME
        photos = emptyList()
        leafProbabilities = null
        kept = false
        picked = null
        caseIndex = (caseIndex + 1) % Demo.cases.size
    }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.RESULT) startOver() else screen = Screen.HOME
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Only home has chrome. Capture and result are full-bleed photographs and draw
            // their own controls over the image, which is what makes them feel like moments.
            if (screen == Screen.HOME) {
                TopAppBar(
                    title = { Text("Life List", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = { thresholdSheet = true }) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = "How sure before it commits",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
    ) { insets ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                when {
                    targetState == Screen.CAPTURE ->
                        (slideInVertically { it } + fadeIn()) togetherWith fadeOut()
                    initialState == Screen.CAPTURE && targetState == Screen.HOME ->
                        fadeIn() togetherWith (slideOutVertically { it } + fadeOut())
                    targetState == Screen.HOME ->
                        (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 4 } + fadeOut())
                    else ->
                        (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 8 } + fadeOut())
                }
            },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.HOME -> Box(Modifier.fillMaxSize().padding(insets)) {
                    HomeScreen(
                        taxonomy = taxonomy,
                        records = records,
                        onOpenRecord = { openRecord = it },
                        onOpenGroup = { },
                    )
                    FloatingActionButton(
                        onClick = { screen = Screen.CAPTURE },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .safeDrawingPadding()
                            .padding(18.dp)
                            .size(68.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "Identify something",
                            modifier = Modifier.size(29.dp),
                        )
                    }
                }

                Screen.CAPTURE -> CaptureScreen(
                    onCapture = { bitmap ->
                        if (bitmap != null) {
                            identify(if (photos.isEmpty()) listOf(bitmap) else photos + bitmap)
                        } else {
                            identify(photos)
                        }
                    },
                    onClose = { screen = if (photos.isEmpty()) Screen.HOME else Screen.RESULT },
                    addingTo = photos.size,
                )

                Screen.THINKING -> ThinkingScreen(
                    photo = photos.firstOrNull(),
                    note = thinkingNote(loaded, identifier),
                )

                Screen.RESULT -> ResultScreen(
                    answer = answer,
                    isFirst = LifeList.isFirst(records, picked?.taxonId ?: answer.taxonId),
                    photos = photos,
                    reference = references.photo(picked?.taxonId ?: answer.taxonId),
                    referenceCredit = references.credit(picked?.taxonId ?: answer.taxonId),
                    article = wikipedia.article(picked?.taxonId ?: answer.taxonId),
                    choices = choices,
                    picked = picked,
                    onPick = { picked = it },
                    onKeep = {
                        val node = picked?.taxonId ?: answer.taxonId
                        if (!kept && node != 0) {
                            // Asked for the first time only when there is something to attach
                            // it to. A sighting is never blocked on the answer.
                            val here = Where.lastKnown(context)
                            if (here == null && !Where.granted(context)) {
                                askWhere.launch(Where.PERMISSION)
                            }
                            val first = LifeList.isFirst(records, node)
                            records = store.add(
                                Record(
                                    id = store.newId(),
                                    taxonId = node,
                                    observedAt = System.currentTimeMillis(),
                                    photoPath = photos.firstOrNull()?.let { store.savePhoto(it) },
                                    threshold = threshold,
                                    modelVersion = loaded?.meta?.version ?: "unknown",
                                    // A tap is not a model prediction and must not be reported
                                    // as one (§20). What the model said is kept alongside.
                                    determinedBy = if (picked != null) Determiner.USER
                                    else Determiner.MODEL,
                                    refinedFrom = if (picked != null) answer.taxonId else null,
                                    confidence = answer.confidence.probability,
                                    latitude = here?.latitude,
                                    longitude = here?.longitude,
                                )
                            )
                            kept = true
                            val total = LifeList.totals(taxonomy, records).taxa
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (first) "Added — that is $total on your list"
                                    else "Added to your list"
                                )
                            }
                        }
                    },
                    onAddPhoto = { screen = Screen.CAPTURE },
                    onRetake = { photos = emptyList(); screen = Screen.CAPTURE },
                    onBack = { startOver() },
                    onOpenPhoto = { bitmap, label -> viewing = Viewing.Live(bitmap, label) },
                    onOpenTaxon = { readingAbout = it },
                    kept = kept,
                    modelNote = note(loaded, identifier, leafProbabilities, photos, failure),
                )
            }
        }
    }

    if (thresholdSheet) {
        ThresholdSheet(
            threshold = threshold,
            onChange = { threshold = it },
            onDismiss = { thresholdSheet = false },
        )
    }

    openRecord?.let { record ->
        RecordSheet(
            taxonomy = taxonomy,
            record = record,
            article = wikipedia.article(record.taxonId),
            onOpenPhoto = { path -> viewing = Viewing.Stored(path) },
            onDismiss = { openRecord = null },
        )
    }

    readingAbout?.let { taxonId ->
        val node = taxonomy.node(taxonId)
        TaxonSheet(
            brief = TaxonBrief(
                taxonId = taxonId,
                name = Presentation.styleName(node.scientificName, node.rank).annotated(),
                vernacular = node.vernacularEn,
                rank = node.rank,
                photo = references.photo(taxonId),
                credit = references.credit(taxonId),
                article = wikipedia.article(taxonId),
            ),
            onOpenPhoto = { bitmap, label -> viewing = Viewing.Live(bitmap, label) },
            onDismiss = { readingAbout = null },
        )
    }

    viewing?.let { PhotoViewer(it, onDismiss = { viewing = null }) }

    LaunchedEffect(loaded) {
        val outcome = loaded?.outcome
        if (outcome is Identifier.Companion.Outcome.Failed) {
            snackbar.showSnackbar("Model failed to load — ${outcome.reason}")
        }
    }
}

private fun thinkingNote(loaded: Loaded?, identifier: Identifier?): String = when {
    loaded == null -> "Getting the model ready — this takes a moment the first time."
    identifier == null -> "No model in this build — showing an example."
    else -> "On this phone. Nothing leaves it."
}

private fun note(
    loaded: Loaded?,
    identifier: Identifier?,
    probabilities: FloatArray?,
    photos: List<Bitmap>,
    failure: String?,
): String = when {
    failure != null -> "Identification failed — $failure"
    loaded == null -> "Getting the model ready…"
    loaded.outcome is Identifier.Companion.Outcome.NotBundled ->
        "No model in this build — showing an example"
    loaded.outcome is Identifier.Companion.Outcome.Failed ->
        "Model failed to load — ${(loaded.outcome as Identifier.Companion.Outcome.Failed).reason}"
    identifier != null && probabilities == null && photos.isNotEmpty() -> "Identifying…"
    identifier != null && probabilities != null ->
        "Model ${loaded.meta?.version ?: "?"} · ${loaded.meta?.nTaxa ?: 0} taxa" +
            if (photos.size > 1) " · ${photos.size} photos fused" else ""
    else -> "Example result — take a photo to use the model"
}

data class Loaded(val outcome: Identifier.Companion.Outcome, val meta: TaxonomyAssets.Meta?)
