package dk.lifelist.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dk.lifelist.core.Determiner
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Rollup
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
 * The two places this app can be.
 *
 * There used to be three *screens* and no app around them — capture, then result, then list,
 * each drawing its own header and its own way back. That is the whole of "it doesn't feel
 * like a coherent app": with no persistent chrome there is nothing to be coherent. A
 * `NavigationBar` that never goes away, and a `TopAppBar` that names where you are, is the
 * cheapest possible fix and it is also the correct one.
 *
 * Result is not a destination. It is what the Identify tab shows once there is something to
 * say, which is why it keeps the tab bar and takes a back arrow rather than a "Close" link.
 */
private enum class Tab(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    IDENTIFY("Identify", Icons.Filled.PhotoCamera, Icons.Outlined.PhotoCamera),
    LIST("My list", Icons.Filled.Checklist, Icons.Outlined.Checklist),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val references = remember { ReferencePhotos(context) }
    val snackbar = remember { SnackbarHostState() }
    // Coarse location, asked for once, at the moment it would first be used.
    val askWhere = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.IDENTIFY) }
    var showingResult by remember { mutableStateOf(false) }
    var thresholdSheet by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }

    var threshold by remember { mutableFloatStateOf(0.70f) }
    var caseIndex by remember { mutableIntStateOf(0) }

    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var leafProbabilities by remember { mutableStateOf<FloatArray?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(false) }
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
    val answer = currentAnswer(identifier, leafProbabilities, caseIndex, threshold)

    fun identify(bitmaps: List<Bitmap>) {
        photos = bitmaps
        leafProbabilities = null
        failure = null
        kept = false
        showingResult = true
        if (bitmaps.isNotEmpty() && identifier != null) {
            thread {
                runCatching { identifier.identify(bitmaps) }.fold(
                    onSuccess = { leafProbabilities = it },
                    onFailure = { failure = "${it::class.simpleName}: ${it.message}" },
                )
            }
        }
    }

    fun startOver() {
        showingResult = false
        photos = emptyList()
        leafProbabilities = null
        kept = false
        caseIndex = (caseIndex + 1) % Demo.cases.size
    }

    BackHandler(enabled = showingResult && tab == Tab.IDENTIFY) { startOver() }
    BackHandler(enabled = tab != Tab.IDENTIFY) { tab = Tab.IDENTIFY }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            tab == Tab.LIST -> "My list"
                            showingResult -> "Identification"
                            else -> "Life List"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (tab == Tab.IDENTIFY && showingResult) {
                        IconButton(onClick = { startOver() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (tab == Tab.IDENTIFY) {
                        IconButton(onClick = { thresholdSheet = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "How sure before it commits")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { entry ->
                    val selected = tab == entry
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (entry == Tab.LIST) records = store.load()
                            tab = entry
                        },
                        icon = {
                            Icon(
                                if (selected) entry.selectedIcon else entry.icon,
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { insets ->
        AnimatedContent(
            targetState = tab to showingResult,
            transitionSpec = {
                val forward = targetState.second || targetState.first.ordinal > initialState.first.ordinal
                val shift = if (forward) 1 else -1
                (slideInHorizontally { it / 6 * shift } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 6 * shift } + fadeOut())
            },
            label = "screen",
            modifier = Modifier.padding(insets),
        ) { (current, result) ->
            Box(Modifier.fillMaxSize()) {
                when {
                    current == Tab.LIST -> LifeListScreen(
                        taxonomy = identifier?.taxonomy ?: Demo.taxonomy,
                        records = records,
                        onOpenPhoto = { path -> viewing = Viewing.Stored(path) },
                    )

                    result -> ResultScreen(
                        answer = answer,
                        threshold = threshold,
                        onOpenThreshold = { thresholdSheet = true },
                        onRetake = { startOver() },
                        onAddPhoto = { extra ->
                            if (extra != null) identify(photos + extra)
                        },
                        onKeep = {
                            if (!kept && answer.taxonId != 0) {
                                // Asked for the first time only when there is something to
                                // attach it to. A sighting is never blocked on the answer.
                                val here = Where.lastKnown(context)
                                if (here == null && !Where.granted(context)) askWhere.launch(Where.PERMISSION)
                                // Stored at the rank the rollup returned, whatever that is —
                                // the whole point (§19). Root is the one thing not worth keeping.
                                records = store.add(
                                    Record(
                                        id = store.newId(),
                                        taxonId = answer.taxonId,
                                        observedAt = System.currentTimeMillis(),
                                        photoPath = photos.firstOrNull()?.let { store.savePhoto(it) },
                                        threshold = threshold,
                                        modelVersion = loaded?.meta?.version ?: "unknown",
                                        determinedBy = Determiner.MODEL,
                                        // Stored, not recomputed: the model will change, and
                                        // a record that re-scores itself later misreports what
                                        // you were actually told at the time.
                                        confidence = answer.confidence.probability,
                                        latitude = here?.latitude,
                                        longitude = here?.longitude,
                                    )
                                )
                                kept = true
                                scope.launch { snackbar.showSnackbar("Added to your list") }
                            }
                        },
                        photos = photos,
                        reference = identifier?.let { references.photo(answer.taxonId) },
                        referenceCredit = identifier?.let { references.credit(answer.taxonId) },
                        onOpenPhoto = { bitmap, label -> viewing = Viewing.Live(bitmap, label) },
                        kept = kept,
                        modelNote = note(loaded, identifier, leafProbabilities, photos, failure),
                    )

                    else -> CaptureScreen(
                        onCapture = { bitmap -> if (bitmap != null) identify(listOf(bitmap)) },
                        note = note(loaded, identifier, leafProbabilities, photos, failure),
                        ready = identifier != null,
                    )
                }
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

    viewing?.let { PhotoViewer(it, onDismiss = { viewing = null }) }

    // The first launch after an install spends ten seconds copying 350 MB out of the APK,
    // and a shutter that silently does nothing in that window reads as a broken app.
    LaunchedEffect(loaded) {
        val outcome = loaded?.outcome
        if (outcome is Identifier.Companion.Outcome.Failed) {
            snackbar.showSnackbar("Model failed to load — ${outcome.reason}")
        }
    }
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
        "Model ${loaded.meta?.version ?: "?"} · ${loaded.meta?.nTaxa ?: 0} taxa"
    else -> "Example result — take a photo to use the model"
}

/**
 * The answer to show: the model's if there is one, otherwise the demo case.
 *
 * Threshold is applied here rather than at capture, so moving the slider re-rolls up
 * probabilities that already exist instead of re-running the model (spec §4.4 — the
 * threshold is a display-time decision).
 */
@Composable
fun currentAnswer(
    identifier: Identifier?,
    probabilities: FloatArray?,
    caseIndex: Int,
    threshold: Float,
) = if (identifier != null && probabilities != null) {
    Presentation.present(
        identifier.taxonomy,
        Rollup.rollup(identifier.taxonomy, probabilities, threshold),
    )
} else {
    answerFor(Demo.cases[caseIndex].probabilities, threshold)
}

data class Loaded(val outcome: Identifier.Companion.Outcome, val meta: TaxonomyAssets.Meta?)
