package dk.lifelist.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Taxonomy
import dk.lifelist.core.standing
import kotlin.math.roundToInt

/**
 * A listening session.
 *
 * A session is an observation **container**, not an observation. Several species really can be
 * singing at once and each one is its own record, savable on its own — which is the shape
 * BUILD.md §3.6 asked for and the shape BirdNET's multi-label output actually has.
 *
 * The screen shows what the app is willing to claim and nothing more. A detection that cannot
 * clear the certainty target is listed at the rank it can defend, or as "not sure enough" —
 * never as a species with a confident number in front of it. Since BirdNET's raw confidences
 * are widely over-trusted in the field, refusing to launder them is the contribution here.
 */

/** One line on the running list: what was heard, and what the app will say about it. */
data class Heard(
    val taxonId: Int,
    val name: String,
    val rank: String,
    /** What the app is willing to claim, after §4A. Null when it will not commit at all. */
    val confidence: Float?,
    /**
     * What the model made of it, whether or not that clears the bar.
     *
     * Kept beside [confidence] rather than folded into it. The app's claim is still binary and
     * still honest — the card says "not sure enough" — but hiding the number threw away the
     * one thing a person standing in a field can act on: whether it was close (§75).
     */
    val probability: Float,
    /** BirdNET's own score for the detection that started this. */
    val detected: Float,
    val atSeconds: Float,
    val alsoConsidered: List<String>,
    /** The threshold this was decided at. Stored on the record so it re-renders honestly
     *  later, exactly as a photographed record does (spec §4.4). */
    val threshold: Float,
    /** The five seconds it was heard in, on disk. Playable, and kept with the record. */
    val clipPath: String? = null,
    val saved: Boolean = false,
)

@Composable
fun ListenScreen(
    listening: Boolean,
    heard: List<Heard>,
    elapsedSeconds: Float,
    permission: Boolean,
    modelReady: Boolean,
    note: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: (Heard) -> Unit,
    /** Path of the clip currently sounding, so one button at a time reads "Stop". */
    playing: String? = null,
    onPlay: (String) -> Unit = {},
    /** The bundled recording of a species, if this build has one. */
    referenceFor: (Int) -> String? = { null },
    /** A small photograph of a species, if this build has one. */
    thumbnailFor: (Int) -> Bitmap? = { null },
    /** The last ten seconds of sound. Null in a preview that has no microphone behind it. */
    spectrogram: SpectrogramState? = null,
    /** True while a clip is sounding and nothing is being identified. */
    muted: Boolean = false,
    /** Whether this build carries reference recordings at all. */
    referencesBundled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Its own ground, rather than the Scaffold's. The palette is ink on paper and there is no
    // dark paper (Theme.kt); a screen that borrows its background is one container away from
    // rendering white text on grey, which is exactly what the first snapshot of it did.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Pinned, not scrolled with the list. It is the only thing on the screen that says the
        // microphone is actually open, and it is no use once it has scrolled away.
        if (spectrogram != null) {
            SpectrogramStrip(
                state = spectrogram,
                listening = listening,
                elapsedSeconds = elapsedSeconds,
                muted = muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (spectrogram == null) item { Level(listening, elapsedSeconds) }

            // An absence the app can see should be an absence the app says. The reference
            // recordings are fetched at release time and the fetcher used to exit 0 having
            // downloaded nothing, so a build could ship with the comparison silently missing
            // and look exactly like a build where no bird happened to have one (§76).
            if (!referencesBundled) {
                item {
                    Text(
                        "This build carries no reference recordings, so there is nothing to " +
                            "compare what you heard against.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (note != null) {
                item {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (heard.isEmpty()) {
                item { Empty(listening) }
            } else {
                item { FieldLabel("Heard so far") }
                items(heard, key = { "${it.taxonId}-${it.atSeconds}" }) { entry ->
                    val reference = referenceFor(entry.taxonId)
                    HeardCard(
                        entry,
                        thumbnail = thumbnailFor(entry.taxonId),
                        playing = playing == entry.clipPath && entry.clipPath != null,
                        referencePlaying = reference != null && playing == reference,
                        hasReference = reference != null,
                        onSave = { onSave(entry) },
                        onPlay = { entry.clipPath?.let(onPlay) },
                        onPlayReference = { reference?.let(onPlay) },
                    )
                }
            }
        }

        Column(Modifier.padding(20.dp)) {
            Button(
                onClick = if (listening) onStop else onStart,
                enabled = permission && modelReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (listening) "Stop listening" else "Listen")
            }
            if (!permission) {
                Text(
                    "Life List needs the microphone to identify sound. It records only while " +
                        "this screen is listening, and nothing leaves the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The one piece of motion on the screen, and it earns its place: with no waveform and no
 * counter, there is nothing to distinguish "listening" from "frozen".
 */
@Composable
private fun Level(listening: Boolean, elapsedSeconds: Float) {
    val transition = rememberInfiniteTransition(label = "listening")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
                .alpha(if (listening) pulse else 1f)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            if (listening) "Listening · ${elapsedSeconds.roundToInt()}s" else "Not listening",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Empty(listening: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (listening) "Nothing yet." else "Point the phone at the sound and press Listen.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (listening) {
            Spacer(Modifier.height(6.dp))
            Text(
                "The first answer takes five seconds — that is how much sound the model listens to at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HeardCard(
    entry: Heard,
    thumbnail: Bitmap?,
    playing: Boolean,
    referencePlaying: Boolean,
    hasReference: Boolean,
    onSave: () -> Unit,
    onPlay: () -> Unit,
    onPlayReference: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        // `surface` is the paper itself in this theme, so a card painted with it is invisible
        // — which is exactly what the first render of this screen showed. Same white and the
        // same 1dp as every other card in the app.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The bird, next to the claim about the bird. Sound identification is the case
            // where you have not seen the thing, so a photograph is not decoration — it is the
            // first way to tell whether the answer is plausible at all.
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(13.dp))
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(if (entry.confidence == null) "Not sure enough" else entry.rank)
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = if (entry.rank == "species" || entry.rank == "genus") {
                        FontStyle.Italic
                    } else {
                        FontStyle.Normal
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The number always; the colour says whether the app will stand behind it. A bare
            // dash told a magpie at 0.77 and a raven at 0.12 apart not at all.
            ConfidenceRing(
                fraction = entry.probability,
                colour = Warm.standingColour(standing(entry.probability, entry.threshold)),
                diameter = 52.dp,
            )
        }
        Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            // Playable whether or not the app was willing to name it. A refusal is exactly the
            // case where you most want to hear what it heard and decide for yourself.
            if (entry.clipPath != null) {
                TextButton(onClick = onPlay) {
                    Text(if (playing) "Stop" else "What it heard")
                }
            }
            // The comparison, right where the doubt is. Hearing the two one after the other is
            // how somebody settles a call they are not sure about — and it is offered on a
            // refusal too, which is the case where the doubt is the whole point.
            if (hasReference) {
                TextButton(onClick = onPlayReference) {
                    Text(if (referencePlaying) "Stop" else "The real thing")
                }
            }
            if (entry.confidence != null) {
                TextButton(onClick = onSave, enabled = !entry.saved) {
                    Text(if (entry.saved) "Added to your list" else "Add to my list")
                }
            }
        }
    }
}

/**
 * The line under the name.
 *
 * It always says what BirdNET actually scored, next to what the app is claiming. Those are two
 * different numbers whenever the confusion set has more than one member, and the gap between
 * them is the honest part.
 */
internal fun subtitle(entry: Heard): String {
    val heard = "heard at ${entry.atSeconds.roundToInt()}s, BirdNET ${percent(entry.detected)}"
    return when {
        entry.alsoConsidered.isEmpty() -> heard
        else -> "$heard · against ${entry.alsoConsidered.joinToString(", ")}"
    }
}

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

/** Build a screen row from one §4A identification. */
fun heardFrom(
    taxonomy: Taxonomy,
    identification: dk.lifelist.core.Audio.AudioIdentification,
    clipPath: String? = null,
): Heard {
    val result = identification.result
    val node = taxonomy.nodeOrNull(result.taxonId)
    val detected = taxonomy.nodeOrNull(identification.detection.taxonId)
    val displayed = if (result.isUnidentified) detected else node

    return Heard(
        taxonId = if (result.isUnidentified) identification.detection.taxonId else result.taxonId,
        name = displayed?.let { it.vernacularEn ?: it.scientificName } ?: "Unknown",
        rank = displayed?.rank ?: "unknown",
        confidence = if (result.isUnidentified) null else result.probability,
        // For a refusal the node being *shown* is the detection, not the root the rollup
        // retreated to, so the number beside it has to be the detection's — `result.probability`
        // there is the root's, which is one minus the absent mass and says nothing about this
        // bird. For a lone detection the two coincide by construction (spec §4A.3), which is
        // why this reads as BirdNET's own score whenever there was nothing to confuse it with.
        probability = if (result.isUnidentified) {
            result.candidates.firstOrNull { it.taxonId == identification.detection.taxonId }
                ?.probability ?: identification.detection.score
        } else {
            result.probability
        },
        detected = identification.detection.score,
        atSeconds = identification.detection.windowStartS,
        clipPath = clipPath,
        alsoConsidered = identification.confusionSet
            .filter { it != identification.detection.taxonId }
            .mapNotNull { taxonomy.nodeOrNull(it) }
            .map { it.vernacularEn ?: it.scientificName },
        threshold = result.threshold,
    )
}
