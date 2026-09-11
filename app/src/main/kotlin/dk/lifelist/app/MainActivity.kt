package dk.lifelist.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import dk.lifelist.core.Certainties
import dk.lifelist.core.CertaintyTable
import dk.lifelist.core.Determiner
import dk.lifelist.core.Families
import dk.lifelist.core.LifeList
import dk.lifelist.core.LocationSource
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Rollup
import dk.lifelist.core.Spectrograph
import dk.lifelist.core.windowOverlaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // First, so a crash during startup is still recorded.
        CrashLog.install(this)
        // Edge-to-edge is the default from targetSdk 35 whether asked for or not, so the
        // choice is only whether the app respects the insets or draws under the buttons.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LifeListTheme { App() } }
    }

    /**
     * Volume down or up takes the picture while the camera is open.
     *
     * Consumed only when the capture screen is listening, so everywhere else in the app the
     * keys still change the volume. Handled on key *down* rather than up, because that is the
     * moment a camera app fires and the difference is felt.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        // repeatCount guards the long press: holding the key would otherwise fire a burst.
        if (isVolume && event?.repeatCount == 0 && VolumeShutter.press()) return true
        return super.onKeyDown(keyCode, event)
    }

    /** Swallowed to match, or the system raises the volume UI when the key comes back up. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolume && VolumeShutter.listening) return true
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * One surface.
 *
 * Home is your life list. Capture is a full-screen moment you enter on purpose and leave with
 * an X. The result slides in over it, and dismissing it puts you back on a list that just grew.
 * There is no tab bar, because there is nowhere else to be.
 *
 * A group opens on top of home rather than replacing it — the counts on the home screen were
 * going nowhere, which made them a scoreboard rather than a way in.
 */
private enum class Screen { HOME, GROUP, CAPTURE, THINKING, RESULT, LISTEN }

/**
 * The location permissions a photograph wants, and does not have yet.
 *
 * Both or neither, in one dialog: a second request raised while the first is up is dropped.
 * Coarse location is for a photograph taken now; `ACCESS_MEDIA_LOCATION` is what stops the
 * gallery handing over pictures with their coordinates stripped (§46).
 */
private fun missingLocationPermissions(context: android.content.Context): Array<String> =
    buildList {
        if (!Where.granted(context)) add(Where.PERMISSION)
        Gallery.MEDIA_LOCATION_PERMISSION?.let {
            if (!Gallery.canReadPhotoLocation(context)) add(it)
        }
    }.toTypedArray()

/** What came back from the camera or the picker, with whatever it knows about itself. */
data class Shot(
    val bitmap: Bitmap,
    val coordinates: Pair<Double, Double>? = null,
    val fromCamera: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val references = remember { ReferencePhotos(context) }
    val wikipedia = remember { Wikipedia(context) }
    val redList = remember { RedList(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()


    var screen by remember { mutableStateOf(Screen.HOME) }
    var group by remember { mutableStateOf<String?>(null) }
    var thresholdSheet by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    var readingAbout by remember { mutableStateOf<Int?>(null) }
    var openRecordId by remember { mutableStateOf<String?>(null) }
    var crash by remember { mutableStateOf(CrashLog.last(context)) }
    // Naming an identification yourself, straight off the result screen.
    var searchingAll by remember { mutableStateOf(false) }
    var keepingBroader by remember { mutableStateOf(false) }

    // The accuracy the person asked for, remembered across launches. The *threshold* is derived
    // from it per group at display time, so the same record re-renders honestly when the model
    // changes underneath it (spec §4.4).
    var target by remember { mutableFloatStateOf(Prefs.target(context)) }
    var caseIndex by remember { mutableIntStateOf(0) }

    // Listening. The session lives on a background thread and posts into these; a session is a
    // container of observations, not one observation, so `heard` is a list that grows.
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<List<Heard>>(emptyList()) }
    var listenedFor by remember { mutableFloatStateOf(0f) }
    var listenNote by remember { mutableStateOf<String?>(null) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val recorder = remember { Recorder() }
    // The picture of the sound, and the thing that fills it. Both outlive a session so the last
    // ten seconds are still on screen after "Stop listening" — the moment you most want to look
    // at what was there is just after you stopped.
    val spectrogram = remember { SpectrogramState() }
    val spectrograph = remember { Spectrograph(sampleRate = Listener.SAMPLE_RATE) }
    // The stretch of the session the app has decided not to trust, because the phone was
    // making the noise. Seconds since the session started, the same clock the windows use.
    var mutedFromS by remember { mutableFloatStateOf(0f) }
    var mutedToS by remember { mutableFloatStateOf(0f) }
    val clipPlayer = rememberClipPlayer()
    val referenceAudio = remember { ReferenceAudio(context) }
    val occurrences = remember { OccurrenceIndex(context) }
    var listener by remember { mutableStateOf<Listener?>(null) }

    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var shotCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var leafProbabilities by remember { mutableStateOf<FloatArray?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Choice?>(null) }
    var records by remember { mutableStateOf(store.load()) }

    // Adding a photograph to a record that already exists, rather than to the one being made.
    var addingPhotoTo by remember { mutableStateOf<String?>(null) }
    // What the model says now that there are more photographs of the same individual. Offered,
    // never applied: the home screen promises "photograph one again and it may settle", and a
    // determination that silently rewrote itself would be a different and worse promise.
    var suggestion by remember { mutableStateOf<Suggestion?>(null) }

    // Loading a 335 MB session takes a moment, so it happens off the main thread and the
    // absence of a model is a state rather than a crash.
    val loaded by produceState<Loaded?>(initialValue = null) {
        value = runCatching {
            val taxonomy = TaxonomyAssets.loadTaxonomy(context)
            val meta = TaxonomyAssets.loadMeta(context)
            Loaded(Identifier.openOrReport(context, taxonomy, meta.temperature), meta, taxonomy)
        }.getOrElse {
            Loaded(Identifier.Companion.Outcome.Failed("assets: ${it.message}"), null, null)
        }
    }
    val identifier = (loaded?.outcome as? Identifier.Companion.Outcome.Ready)?.identifier

    /**
     * The tree the saved list is read against. Null only while the asset is still loading.
     *
     * v0.7.1 crashed on launch for anyone holding a single record because this was
     * `if (live) identifier.taxonomy else Demo.taxonomy` — and `live` requires an
     * identification to have already happened, so on every cold start the home screen was
     * handed a nineteen-node stand-in and asked to look up real taxa in it. `Taxonomy.node`
     * throws. There is no demo fallback here now, and there must never be one again: the demo
     * exists to give the *result* screen something to show, and the list is not the result
     * screen.
     */
    val listTaxonomy = loaded?.taxonomy

    val addPhotoToRecord = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
    ) { uris ->
        val id = addingPhotoTo
        addingPhotoTo = null
        if (uris.isNotEmpty() && id != null) {
            val added = uris.mapNotNull { runCatching { decodeSoftware(context, it) }.getOrNull() }
            val record = records.firstOrNull { it.id == id }
            if (added.isNotEmpty() && record != null) {
                val updated = record.copy(
                    photoPaths = (record.photoPaths + store.savePhotos(added)).take(MAX_PHOTOS)
                )
                records = store.update(updated)
                suggestion = null
                reIdentify(context, identifier, store, updated) { suggestion = it }
            }
        }
    }


    val live = identifier != null && leafProbabilities != null
    val answerTaxonomy = if (live) identifier!!.taxonomy else Demo.taxonomy
    val probabilities = if (live) leafProbabilities!! else Demo.cases[caseIndex].probabilities

    val certaintyTable = loaded?.meta?.certainty ?: CertaintyTable.EMPTY
    val targeted = Certainties.rollup(answerTaxonomy, probabilities, certaintyTable, target)
    val rollup = targeted.result
    val threshold = targeted.certainty.threshold
    val answer = Presentation.present(answerTaxonomy, rollup)

    // The contenders a hedge can hand back. Built from the full probability vector rather than
    // the top-five candidate list, so a genus holding one of the top five still gets to ask.
    val choices = remember(rollup, answerTaxonomy) {
        LifeList.choices(answerTaxonomy, probabilities, rollup.taxonId).map { candidate ->
            val node = answerTaxonomy.node(candidate.taxonId)
            val confidence = Presentation.confidence(candidate.probability)
            Choice(
                taxonId = candidate.taxonId,
                name = Presentation.styleName(node.scientificName, node.rank).annotated(),
                vernacular = node.vernacularEn,
                percent = confidence.percent,
                fraction = confidence.barFraction,
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

    fun took(shots: List<Shot>) {
        if (shots.isEmpty()) {
            identify(photos)
            return
        }
        // Straight to the camera roll, before anything else can go wrong. Losing a photograph
        // by backing out of the wrong screen is not a trade-off anyone agreed to.
        val fromCamera = shots.filter { it.fromCamera }
        if (fromCamera.isNotEmpty()) {
            thread { fromCamera.forEach { Gallery.save(context, it.bitmap) } }
        }
        // A picture knows where it was taken; the phone only knows where it is now.
        shots.firstNotNullOfOrNull { it.coordinates }?.let { shotCoordinates = it }
        identify((photos + shots.map { it.bitmap }).take(MAX_PHOTOS))
    }

    // Straight from the home screen into an identification, skipping the camera entirely.
    // Same contract as the capture screen's picker: several images, one sighting. Declared
    // after `took` because that is what it calls, and a local function does not exist above
    // its own declaration.
    //
    // Decoding runs off the main thread: six full-resolution photographs is a visible stall
    // on the frame that should be drawing the transition into the thinking screen.
    val pickForNewRecord = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val shots = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching { decodeSoftware(context, uri) }.getOrNull()?.let { bitmap ->
                            Shot(bitmap, Gallery.coordinatesOf(context, uri))
                        }
                    }
                }
                if (shots.isNotEmpty()) took(shots)
            }
        }
    }

    // Both location permissions in one request rather than two launchers firing together: a
    // second dialog raised while the first is up is simply dropped. The media one is here
    // because without it every photograph chosen from the gallery arrives with its coordinates
    // stripped — see Gallery.coordinatesOf. Declining either costs a field on the record,
    // never a sighting.
    //
    // Declared after `pickForNewRecord` because it finishes by opening it: the gallery button
    // has to ask *before* the picker rather than on the way into the camera it never visits,
    // and a dialog the user answers should hand them straight on to what they were doing.
    var pickAfterPermissions by remember { mutableStateOf(false) }

    val askWhere = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pickAfterPermissions) {
            pickAfterPermissions = false
            pickForNewRecord.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    fun startOver() {
        screen = Screen.HOME
        photos = emptyList()
        shotCoordinates = null
        leafProbabilities = null
        kept = false
        picked = null
        caseIndex = (caseIndex + 1) % Demo.cases.size
    }

    // Back, in the order a person means it. Un-picking a species used to throw the whole
    // identification away and land on the home screen, so the photograph had to be taken again.
    BackHandler(enabled = screen != Screen.HOME) {
        when {
            screen == Screen.RESULT && picked != null -> picked = null
            screen == Screen.RESULT -> startOver()
            screen == Screen.LISTEN -> { recorder.stop(); screen = Screen.HOME }
            screen == Screen.CAPTURE && photos.isNotEmpty() -> screen = Screen.RESULT
            else -> { screen = Screen.HOME; group = null }
        }
    }

    // Asked while the user is already granting the camera, not after the first sighting is
    // ready to be saved — which was too late for the record that prompted it.
    LaunchedEffect(screen) {
        if (screen != Screen.CAPTURE) return@LaunchedEffect
        val wanted = missingLocationPermissions(context)
        if (wanted.isNotEmpty()) askWhere.launch(wanted)
    }

    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    /**
     * Run a session on a background thread.
     *
     * Every window is scored and identified as it arrives, so the list grows while the bird is
     * still singing. A species already on the list is *updated* rather than repeated when a
     * later window hears it better — a blackbird singing for two minutes is one record, not
     * twenty-four, and the best window is the one worth keeping.
     */
    fun startListening() {
        val model = listener ?: run {
            listenNote = "The audio model is not in this build."
            return
        }
        heard = emptyList()
        listenedFor = 0f
        listening = true
        listenNote = null
        spectrograph.reset()
        spectrogram.clear()
        mutedFromS = 0f
        mutedToS = 0f
        thread {
            runCatching {
                var captured = 0L
                recorder.record(
                    // On the microphone thread, so it has to be cheap: one 2048-point FFT per
                    // 50 ms of audio, which is about a thousandth of the budget the model
                    // spends on the same audio a moment later.
                    onSamples = { samples ->
                        spectrogram.offer(spectrograph.add(samples))
                        // The counter ticks from the audio, four times a second, rather than
                        // from a window boundary every two and a half — which made it jump by
                        // three seconds and then two.
                        captured += samples.size
                        listenedFor = captured.toFloat() / Listener.SAMPLE_RATE
                    },
                ) { window ->
                    // Anything the phone's own speaker was sounding through is not evidence.
                    // Without this the app identifies its own playback and the confidence
                    // climbs with every replay, which looks exactly like growing certainty.
                    if (windowOverlaps(window.startS, 5f, mutedFromS, mutedToS)) return@record

                    val found = model.listen(
                        window.samples,
                        target = target,
                        windowStartS = window.startS,
                    )
                    if (found.isEmpty()) return@record
                    // The window is written once and shared by every detection in it: three
                    // birds singing at 0:35 were all in the same five seconds, and keeping three
                    // copies of the same audio would be a megabyte to say so.
                    val clip = runCatching {
                        store.saveClip(window.samples, Listener.SAMPLE_RATE)
                    }.getOrNull()
                    val fresh = found.map { heardFrom(model.taxonomy, it, clip) }
                    heard = (heard + fresh)
                        .groupBy { it.taxonId }
                        .map { (_, rows) -> rows.maxByOrNull { it.confidence ?: it.detected }!! }
                        .sortedByDescending { it.confidence ?: it.detected }
                }
            }.onFailure { error ->
                listenNote = "The microphone stopped: ${error.message ?: error::class.simpleName}"
            }
            listening = false
        }
    }

    // Opened when the screen is first visited, not at launch: mapping 149 MB for a feature
    // nobody has opened is a second of cold start spent on nothing.
    LaunchedEffect(screen) {
        if (screen != Screen.LISTEN || listener != null) return@LaunchedEffect
        thread {
            when (val outcome = Listener.openOrReport(context)) {
                is Listener.Companion.Outcome.Ready -> listener = outcome.listener
                is Listener.Companion.Outcome.NotBundled ->
                    listenNote = "This build does not carry the audio model."
                is Listener.Companion.Outcome.Failed ->
                    listenNote = "The audio model would not open — ${outcome.reason}"
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when (screen) {
                Screen.HOME -> TopAppBar(
                    title = { Text("Life List", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = { thresholdSheet = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "How sure before it commits")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )

                Screen.LISTEN -> TopAppBar(
                    title = { Text("Listen", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { recorder.stop(); screen = Screen.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )

                Screen.GROUP -> TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = { screen = Screen.HOME; group = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )

                // Capture and result are full-bleed photographs and draw their own controls
                // over the image, which is what makes them feel like moments.
                else -> Unit
            }
        },
    ) { insets ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                when {
                    targetState == Screen.CAPTURE ->
                        (slideInVertically { it } + fadeIn()) togetherWith fadeOut()
                    initialState == Screen.CAPTURE && targetState != Screen.THINKING ->
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
                    if (listTaxonomy == null) {
                        Opening()
                    } else {
                        HomeScreen(
                            taxonomy = listTaxonomy,
                            records = records,
                            onOpenRecord = { openRecordId = it.id },
                            onOpenGroup = { group = it; screen = Screen.GROUP },
                        )
                    }
                    // Two ways in, because there are two ways a sighting happens: pointing the
                    // phone at something now, and coming back to the pictures you already
                    // took. The second was reachable only *through* the camera, which is a
                    // strange thing to make somebody open in order to say "not the camera".
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .safeDrawingPadding()
                            .padding(18.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                photos = emptyList()
                                // Ask *before* the picker, not on the way into the camera.
                                // This button never visits the capture screen, so a photograph
                                // chosen here used to arrive with its coordinates stripped and
                                // the record honestly but wrongly said "from your phone" (§56).
                                val wanted = missingLocationPermissions(context)
                                if (wanted.isEmpty()) {
                                    pickForNewRecord.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                } else {
                                    pickAfterPermissions = true
                                    askWhere.launch(wanted)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = "Identify from your photos",
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                screen = Screen.LISTEN
                                if (!micGranted) askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                Icons.Outlined.GraphicEq,
                                contentDescription = "Identify a sound",
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        FloatingActionButton(
                            onClick = { photos = emptyList(); screen = Screen.CAPTURE },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(68.dp),
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = "Identify something",
                                modifier = Modifier.size(29.dp),
                            )
                        }
                    }
                }

                Screen.GROUP -> Box(Modifier.fillMaxSize().padding(insets)) {
                    if (listTaxonomy == null) {
                        Opening()
                    } else {
                        GroupScreen(
                            taxonomy = listTaxonomy,
                            label = group.orEmpty(),
                            records = records.filter {
                                LifeList.groupOf(listTaxonomy, it.taxonId) == group
                            },
                            onOpenRecord = { openRecordId = it.id },
                            danishTotals = redList.familyTotals,
                            onOpenTaxon = { readingAbout = it },
                            thumbnailFor = { references.thumbnail(it) },
                        )
                    }
                }

                Screen.LISTEN -> Box(Modifier.fillMaxSize().padding(insets)) {
                    ListenScreen(
                        spectrogram = spectrogram,
                        muted = listening && mutedToS > listenedFor,
                        playing = clipPlayer.playing,
                        onPlay = { path ->
                            // Mute first, unmute on the way out: a clip that starts sounding
                            // before the flag is set has already leaked into a window.
                            val at = listenedFor
                            val sounding = clipPlayer.toggle(path) {
                                mutedToS = listenedFor
                            }
                            if (sounding != null) {
                                mutedFromS = at
                                mutedToS = Float.POSITIVE_INFINITY
                            } else {
                                mutedToS = listenedFor
                            }
                        },
                        referenceFor = { referenceAudio.clip(it) },
                        referencesBundled = referenceAudio.available,
                        thumbnailFor = { references.thumbnail(it) },
                        listening = listening,
                        heard = heard,
                        elapsedSeconds = listenedFor,
                        permission = micGranted,
                        modelReady = listener != null,
                        note = listenNote,
                        onStart = ::startListening,
                        onStop = { recorder.stop() },
                        onSave = { entry ->
                            val paths = emptyList<String>()
                            val id = store.newId()
                            records = store.add(
                                Record(
                                    id = id,
                                    taxonId = entry.taxonId,
                                    observedAt = System.currentTimeMillis(),
                                    photoPaths = paths,
                                    threshold = entry.threshold,
                                    modelVersion = loaded?.meta?.version ?: "unknown",
                                    determinedBy = Determiner.MODEL,
                                    confidence = entry.confidence,
                                    clipPath = entry.clipPath,
                                )
                            )
                            heard = heard.map { if (it.taxonId == entry.taxonId) it.copy(saved = true) else it }
                            scope.launch { snackbar.showSnackbar("Added to your list") }
                            // Where, filled in afterwards on a background thread — the same
                            // shape the photographed path uses. A fix takes up to 2.5 s and
                            // the record should land the instant the button is pressed.
                            // DEVICE, not PHOTO: the phone knows where it is, and for a sound
                            // heard a moment ago that is the same place. A photograph is the
                            // case where those two can differ by three days and a sofa (§34).
                            thread {
                                val fix = Where.current(context) ?: return@thread
                                val place = Where.describe(context, fix.latitude, fix.longitude)
                                val stored = store.load().firstOrNull { it.id == id } ?: return@thread
                                records = store.update(
                                    stored.copy(
                                        latitude = fix.latitude,
                                        longitude = fix.longitude,
                                        place = place,
                                        locationSource = LocationSource.DEVICE,
                                    )
                                )
                            }
                        },
                    )
                }

                Screen.CAPTURE -> CaptureScreen(
                    onCapture = { took(it) },
                    onClose = { if (photos.isEmpty()) startOver() else screen = Screen.RESULT },
                    addingTo = photos.size,
                )

                Screen.THINKING -> ThinkingScreen(
                    photo = photos.lastOrNull(),
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
                    keepable = live,
                    onKeep = {
                        val node = picked?.taxonId ?: answer.taxonId
                        // `live` guards the store: the demo taxonomy's ids mean nothing to the
                        // real one, and saving one would put a record in the list that no
                        // taxonomy can resolve.
                        if (live && !kept && node != 0) {
                            kept = true
                            val first = LifeList.isFirst(records, node)
                            val paths = store.savePhotos(photos)
                            val id = store.newId()
                            records = store.add(
                                Record(
                                    id = id,
                                    taxonId = node,
                                    observedAt = System.currentTimeMillis(),
                                    photoPaths = paths,
                                    threshold = threshold,
                                    modelVersion = loaded?.meta?.version ?: "unknown",
                                    // A tap is not a model prediction and must not be reported
                                    // as one (§20). What the model said is kept alongside.
                                    determinedBy = if (picked != null) Determiner.USER
                                    else Determiner.MODEL,
                                    refinedFrom = if (picked != null) answer.taxonId else null,
                                    confidence = answer.confidence.probability,
                                )
                            )
                            val total = LifeList.totals(listTaxonomy ?: answerTaxonomy, records).taxa
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (first) "Added — that is $total on your list"
                                    else "Added to your list"
                                )
                            }
                            // Off the main thread: a fix waits up to 2.5 s and reverse
                            // geocoding hits the network. The record is already saved, so a
                            // slow or absent answer costs nothing.
                            val exif = shotCoordinates
                            thread {
                                val latitude: Double?
                                val longitude: Double?
                                val source: LocationSource
                                if (exif != null) {
                                    latitude = exif.first; longitude = exif.second
                                    source = LocationSource.PHOTO
                                } else {
                                    val fix = Where.current(context)
                                    latitude = fix?.latitude; longitude = fix?.longitude
                                    source = LocationSource.DEVICE
                                }
                                if (latitude != null && longitude != null) {
                                    val place = Where.describe(context, latitude, longitude)
                                    val stored = store.load().firstOrNull { it.id == id }
                                    if (stored != null) {
                                        records = store.update(
                                            stored.copy(
                                                latitude = latitude,
                                                longitude = longitude,
                                                place = place,
                                                locationSource = source,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onAddPhoto = { screen = Screen.CAPTURE },
                    onRetake = { photos = emptyList(); screen = Screen.CAPTURE },
                    onBack = { if (picked != null) picked = null else startOver() },
                    onOpenPhoto = { bitmap, label -> viewing = Viewing.Live(bitmap, label) },
                    onOpenTaxon = { readingAbout = it },
                    onSearchAll = { searchingAll = true },
                    onKeepBroader = { keepingBroader = true },
                    kept = kept,
                    modelNote = note(loaded, identifier, leafProbabilities, photos, failure),
                )
            }
        }
    }

    if (thresholdSheet) {
        ThresholdSheet(
            target = target,
            table = certaintyTable,
            certainty = if (live) targeted.certainty else null,
            onChange = {
                target = it
                Prefs.setTarget(context, it)
            },
            onDismiss = { thresholdSheet = false },
        )
    }

    // Resolved rather than asserted, and with no state written during composition —
    // assigning to `openRecordId` from here would schedule a recomposition from inside one.
    // Looked up by id rather than held as an object, so an edit made inside the sheet is
    // visible in the sheet that made it.
    val openRecord = openRecordId?.let { id -> records.firstOrNull { it.id == id } }
    if (openRecord != null && listTaxonomy != null) {
        RecordSheet(
            playingClip = clipPlayer.playing,
            onPlayClip = { clipPlayer.toggle(it) },
            referenceAudio = referenceAudio,
            taxonomy = listTaxonomy,
            record = openRecord,
            article = wikipedia.article(openRecord.taxonId),
            redListStatus = redList.notable(openRecord.taxonId),
            familyProgress = remember(openRecord.taxonId, records, redList) {
                runCatching {
                    Families.progressFor(
                        listTaxonomy, records, openRecord.taxonId, redList.familyTotals
                    )
                }.getOrNull()
            },
            onOpenPhoto = { path -> viewing = Viewing.Stored(path) },
            onAddPhoto = {
                addingPhotoTo = openRecord.id
                addPhotoToRecord.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRefine = { taxonId ->
                records = store.update(
                    LifeList.refine(listTaxonomy, openRecord, taxonId, Determiner.USER)
                )
                scope.launch { snackbar.showSnackbar("Settled — saved as your determination") }
            },
            onCorrect = { taxonId ->
                records = store.update(
                    LifeList.correct(listTaxonomy, openRecord, taxonId, Determiner.USER)
                )
                suggestion = null
                scope.launch { snackbar.showSnackbar("Corrected — saved as your determination") }
            },
            onEdit = { edited ->
                records = store.update(edited)
                scope.launch { snackbar.showSnackbar("Saved") }
            },
            onDelete = {
                // Held rather than written over, so "Undo" has something to put back. The
                // photographs are left on disk until the snackbar goes: a life list that
                // deletes the picture the instant you tap is one you cannot change your mind
                // about, and the whole reason this button exists is that people do.
                val removed = openRecord
                openRecordId = null
                records = store.delete(removed.id)
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "Sighting deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        records = store.add(removed)
                    } else {
                        thread { store.deletePhotos(removed.photoPaths) }
                    }
                }
            },
            suggestion = suggestion?.takeIf { it.recordId == openRecord.id },
            onUseSuggestion = {
                suggestion?.let { offer ->
                    records = store.update(
                        openRecord.copy(
                            taxonId = offer.taxonId,
                            confidence = offer.probability,
                            // Still the model's call, not the user's — it simply had more to
                            // look at. Recording it as a user determination would be a lie.
                            determinedBy = Determiner.MODEL,
                            refinedFrom = openRecord.refinedFrom ?: openRecord.taxonId,
                        )
                    )
                    suggestion = null
                }
            },
            onDismissSuggestion = { suggestion = null },
            onDismiss = { openRecordId = null },
        )
    }

    // Same shape: a taxon the current tree does not contain simply opens nothing.
    val aboutId = readingAbout
    val aboutNode = aboutId?.let { (listTaxonomy ?: answerTaxonomy).nodeOrNull(it) }
    if (aboutId != null && aboutNode != null) {
        TaxonSheet(
            brief = TaxonBrief(
                taxonId = aboutId,
                name = Presentation.styleName(aboutNode.scientificName, aboutNode.rank).annotated(),
                vernacular = aboutNode.vernacularEn,
                rank = aboutNode.rank,
                photo = references.photo(aboutId),
                credit = references.credit(aboutId),
                article = wikipedia.article(aboutId),
                clip = referenceAudio.clip(aboutId),
                clipCredit = referenceAudio.credit(aboutId),
                occurrence = occurrences.forTaxon(aboutId),
                family = Families.familyOf(listTaxonomy ?: answerTaxonomy, aboutId)?.scientificName,
            ),
            onOpenPhoto = { bitmap, label -> viewing = Viewing.Live(bitmap, label) },
            onDismiss = { readingAbout = null },
            playingClip = clipPlayer.playing,
            onPlayClip = { clipPlayer.toggle(it) },
        )
    }

    if (searchingAll) {
        TaxonSearchSheet(
            taxonomy = answerTaxonomy,
            onPick = { taxon ->
                searchingAll = false
                // Becomes the answer on screen, with no percentage attached — there is no
                // model number behind a name the user found themselves.
                picked = Choice(
                    taxonId = taxon.taxonId,
                    name = Presentation.styleName(taxon.scientificName, taxon.rank).annotated(),
                    vernacular = taxon.vernacularEn,
                    percent = null,
                    fraction = null,
                    photo = references.photo(taxon.taxonId),
                )
            },
            onDismiss = { searchingAll = false },
        )
    }

    if (keepingBroader) {
        BroaderSheet(
            taxonomy = answerTaxonomy,
            taxonId = picked?.taxonId ?: answer.taxonId,
            onPick = { taxon ->
                keepingBroader = false
                picked = Choice(
                    taxonId = taxon.taxonId,
                    name = Presentation.styleName(taxon.scientificName, taxon.rank).annotated(),
                    vernacular = taxon.vernacularEn,
                    percent = null,
                    fraction = null,
                    photo = references.photo(taxon.taxonId),
                )
            },
            onDismiss = { keepingBroader = false },
        )
    }

    crash?.let { report ->
        CrashSheet(report, onDismiss = { CrashLog.clear(context); crash = null })
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

/**
 * What came back from the asset load.
 *
 * The taxonomy is held here rather than only on the [Identifier], because the two fail
 * independently: a 350 MB ONNX session can refuse to open on a device where an 850 kB JSON
 * file parses perfectly. The life list needs only the taxonomy, so it should not be taken
 * hostage by the model.
 */
data class Loaded(
    val outcome: Identifier.Companion.Outcome,
    val meta: TaxonomyAssets.Meta?,
    val taxonomy: dk.lifelist.core.Taxonomy? = null,
)


/**
 * The second before the taxonomy is parsed.
 *
 * Not a spinner over an empty list: an empty life list and a life list that has not loaded yet
 * look identical, and telling the user they have collected nothing when they have collected
 * forty things is its own small betrayal.
 */
@Composable
private fun Opening() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
        )
    }
}


/**
 * What the model says once a sighting has more photographs than it did.
 *
 * A record is a claim made at a moment, and adding evidence is allowed to change the claim —
 * the home screen says so in as many words: "photograph one again and it may settle." What is
 * not allowed is changing it quietly, so this is an offer with a name and a number on it.
 */
data class Suggestion(
    val recordId: String,
    val taxonId: Int,
    val name: String,
    val percent: String,
    val probability: Float,
)

/**
 * Re-run the model over every photograph a record now holds.
 *
 * Off the main thread: this is several inference passes and a set of JPEG decodes. Silent about
 * everything except a genuinely different answer — telling someone "it still thinks the same
 * thing" every time they add a picture is noise, and a record the *user* determined is not the
 * model's to reopen at all.
 */
private fun reIdentify(
    context: android.content.Context,
    identifier: Identifier?,
    store: RecordStore,
    record: Record,
    onSuggestion: (Suggestion?) -> Unit,
) {
    if (identifier == null || record.determinedBy == Determiner.USER) return
    if (record.photoPaths.size < 2) return

    thread {
        runCatching {
            val bitmaps = record.photoPaths.mapNotNull { path ->
                android.graphics.BitmapFactory.decodeFile(path)
            }
            if (bitmaps.size < 2) return@runCatching null

            val probabilities = identifier.identify(bitmaps)
            val rollup = Rollup.rollup(identifier.taxonomy, probabilities, record.threshold)
            if (rollup.taxonId == record.taxonId || rollup.taxonId == 0) return@runCatching null

            val node = identifier.taxonomy.node(rollup.taxonId)
            Suggestion(
                recordId = record.id,
                taxonId = rollup.taxonId,
                name = node.vernacularEn ?: node.scientificName,
                percent = Presentation.confidence(rollup.probability).percent,
                probability = rollup.probability,
            )
        }.onSuccess { onSuggestion(it) }
    }
}
