package dk.lifelist.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import dk.lifelist.core.Presentation
import dk.lifelist.core.RollupResult
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

@Composable
fun App() {
    val context = LocalContext.current
    var showingCamera by remember { mutableStateOf(true) }
    var threshold by remember { mutableFloatStateOf(0.70f) }
    // Rotates through the demo cases, used only when no model asset is present.
    var caseIndex by remember { mutableIntStateOf(0) }

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

    // The photograph the model actually looked at, and the answer it gave. Null means no
    // model or no camera, in which case the demo cases stand in.
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var leafProbabilities by remember { mutableStateOf<FloatArray?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val identifier = (loaded?.outcome as? Identifier.Companion.Outcome.Ready)?.identifier

    if (showingCamera) {
        CameraScreen(onCapture = { captured ->
            photo = captured
            failure = null
            if (captured != null && identifier != null) {
                // Inference is hundreds of milliseconds; off the main thread.
                thread {
                    val outcome = runCatching {
                        identifier.probabilities(identifier.logits(captured))
                    }
                    outcome.fold(
                        onSuccess = { probabilities -> leafProbabilities = probabilities },
                        onFailure = { failure = "${it::class.simpleName}: ${it.message}" },
                    )
                }
            }
            showingCamera = false
        })
    } else {
        ResultScreen(
            answer = currentAnswer(identifier, leafProbabilities, caseIndex, threshold),
            threshold = threshold,
            onThresholdChange = { threshold = it },
            onRetake = {
                leafProbabilities = null
                caseIndex = (caseIndex + 1) % Demo.cases.size
                showingCamera = true
            },
            modelNote = when (val outcome = loaded?.outcome) {
                null -> "Loading model…"
                is Identifier.Companion.Outcome.Ready ->
                    "Model ${loaded?.meta?.version ?: "?"} · ${loaded?.meta?.nTaxa ?: 0} taxa"
                is Identifier.Companion.Outcome.NotBundled ->
                    "No model in this build — showing example results"
                // Shown in full on purpose. A model that is present and broken is a bug
                // report, and the reason belongs where the person holding the phone is.
                is Identifier.Companion.Outcome.Failed ->
                    "Model failed to load — ${outcome.reason}"
            }.let { note ->
                when {
                    failure != null -> "Identification failed — $failure"
                    identifier != null && leafProbabilities == null && photo != null ->
                        "Identifying…"
                    identifier != null && leafProbabilities != null -> note
                    else -> "$note (example)"
                }
            },
        )
    }
}

/**
 * The answer to show: the model's if there is one, otherwise the demo case.
 *
 * Threshold is applied here rather than at capture, so moving the slider re-rolls up the
 * probabilities that were already computed instead of re-running the model (spec §4.4 —
 * the threshold is a display-time decision).
 */
@Composable
fun currentAnswer(
    identifier: Identifier?,
    probabilities: FloatArray?,
    caseIndex: Int,
    threshold: Float,
) = if (identifier != null && probabilities != null) {
    Presentation.present(identifier.taxonomy, Rollup.rollup(identifier.taxonomy, probabilities, threshold))
} else {
    answerFor(Demo.cases[caseIndex].probabilities, threshold)
}

data class Loaded(val outcome: Identifier.Companion.Outcome, val meta: TaxonomyAssets.Meta?)
