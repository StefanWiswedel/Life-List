package dk.lifelist.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dk.lifelist.core.Determiner
import dk.lifelist.core.Presentation
import dk.lifelist.core.Record
import dk.lifelist.core.Rollup
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

private enum class Screen { CAMERA, RESULT, LIST }

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val references = remember { ReferencePhotos(context) }

    var screen by remember { mutableStateOf(Screen.CAMERA) }
    var threshold by remember { mutableFloatStateOf(0.70f) }
    var caseIndex by remember { mutableIntStateOf(0) }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
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

    when (screen) {
        Screen.CAMERA -> CameraScreen(onCapture = { captured ->
            photo = captured
            leafProbabilities = null
            failure = null
            kept = false
            if (captured != null && identifier != null) {
                thread {
                    runCatching { identifier.probabilities(identifier.logits(captured)) }.fold(
                        onSuccess = { leafProbabilities = it },
                        onFailure = { failure = "${it::class.simpleName}: ${it.message}" },
                    )
                }
            }
            screen = Screen.RESULT
        })

        Screen.RESULT -> ResultScreen(
            answer = answer,
            threshold = threshold,
            onThresholdChange = { threshold = it },
            onRetake = {
                leafProbabilities = null
                photo = null
                kept = false
                caseIndex = (caseIndex + 1) % Demo.cases.size
                screen = Screen.CAMERA
            },
            onKeep = {
                if (!kept) {
                    // Stored at the rank the rollup returned, whatever that is — the whole
                    // point (§19). Root is the one thing not worth keeping.
                    val node = answer.taxonId
                    if (node != 0) {
                        val record = Record(
                            id = store.newId(),
                            taxonId = node,
                            observedAt = System.currentTimeMillis(),
                            photoPath = photo?.let { store.savePhoto(it) },
                            threshold = threshold,
                            modelVersion = loaded?.meta?.version ?: "unknown",
                            determinedBy = Determiner.MODEL,
                        )
                        records = store.add(record)
                        kept = true
                    }
                }
            },
            onOpenList = { records = store.load(); screen = Screen.LIST },
            photo = photo,
            reference = identifier?.let { references.photo(answer.taxonId) },
            referenceCredit = identifier?.let { references.credit(answer.taxonId) },
            kept = kept,
            modelNote = note(loaded, identifier, leafProbabilities, photo, failure),
        )

        Screen.LIST -> {
            val taxonomy = identifier?.taxonomy ?: Demo.taxonomy
            LifeListScreen(
                taxonomy = taxonomy,
                records = records,
                onBack = { screen = if (photo != null) Screen.RESULT else Screen.CAMERA },
            )
        }
    }
}

private fun note(
    loaded: Loaded?,
    identifier: Identifier?,
    probabilities: FloatArray?,
    photo: Bitmap?,
    failure: String?,
): String = when {
    failure != null -> "Identification failed — $failure"
    loaded == null -> "Loading model…"
    loaded.outcome is Identifier.Companion.Outcome.NotBundled ->
        "No model in this build — showing an example"
    loaded.outcome is Identifier.Companion.Outcome.Failed ->
        "Model failed to load — ${(loaded.outcome as Identifier.Companion.Outcome.Failed).reason}"
    identifier != null && probabilities == null && photo != null -> "Identifying…"
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
