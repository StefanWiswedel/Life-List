package dk.lifelist.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.lifelist.core.Characters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Going deeper than the photograph could — spec §5, VERIFICATION.md §95.
 *
 * Shown when the answer stopped above species and the app knows what it was choosing between.
 * **Which half is offered depends on whether the animal might still be there**, and that is the
 * whole design rather than a detail:
 *
 * - A record made from the camera a moment ago gets *"photograph the face, from the front"* —
 *   because the app already fuses several photographs of one individual (§3.2), and a picture
 *   is evidence where a memory is a guess wearing evidence's clothes.
 * - A record made from the camera roll, or revisited later, gets the question instead, because
 *   asking is all that is left and a weaker answer beats none.
 *
 * Asked for exactly this way: *"I want both 'photograph the face' and an interactive key if I'm
 * working on older photos or something I can't photograph."*
 */
object Narrow {

    @Serializable
    data class RawOption(val label: String, val taxa: List<Int>, val reliability: Float = Characters.DEFAULT_RELIABILITY)

    @Serializable
    data class RawCharacter(
        val key: String,
        val prompt: String,
        val options: List<RawOption>,
        val source: String = "",
        val note: String = "",
    )

    @Serializable
    data class RawDifference(
        val taxon_id: Int,
        val name: String,
        val says: List<String>,
        val source: String = "",
    )

    @Serializable
    data class RawGuide(
        val genus: String = "",
        val view: String = "",
        val characters: List<RawCharacter> = emptyList(),
        val differences: List<RawDifference> = emptyList(),
    )

    fun guideOf(raw: RawGuide, taxonId: Int) = Characters.Guide(
        taxonId = taxonId,
        view = raw.view,
        characters = raw.characters.map { character ->
            Characters.Character(
                key = character.key,
                prompt = character.prompt,
                options = character.options.map {
                    Characters.Option(it.label, it.taxa, it.reliability)
                },
                source = character.source,
                note = character.note,
            )
        },
    )
}

/** The bundled guides, parsed once and only when a determination actually stops short. */
class CharacterAssets(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val guides: Map<Int, Narrow.RawGuide> by lazy {
        runCatching {
            context.assets.open(ASSET).use { input ->
                json.decodeFromString<Map<String, Narrow.RawGuide>>(
                    input.readBytes().decodeToString()
                ).mapKeys { it.key.toInt() }
            }
        }.getOrDefault(emptyMap())
    }

    /** The guide for a node, if one was written. Looked up on the node itself, then its parents. */
    fun forLineage(lineage: List<Int>): Pair<Int, Narrow.RawGuide>? =
        lineage.asReversed().firstNotNullOfOrNull { id ->
            guides[id]?.let { id to it }
        }

    val available: Boolean get() = guides.isNotEmpty()

    companion object {
        const val ASSET = "characters.json"
    }
}

/**
 * The section itself.
 *
 * Every sentence here is quoted from a source that is named, and nothing is paraphrased. A
 * character written from memory is an error nobody can trace, and the one output this app
 * cannot afford is a confident wrong answer — every other failure here degrades to "I am not
 * sure", and this one would degrade to lying.
 */
@Composable
fun NarrowSection(
    guide: Characters.Guide,
    differences: List<Narrow.RawDifference>,
    /** True when the photograph was taken a moment ago, so the animal may still be there. */
    fresh: Boolean,
    candidates: List<Int>,
    answered: Map<String, String>,
    onAnswer: (Characters.Character, String) -> Unit,
    onAddPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = guide.applicable(candidates)
    if (guide.view.isBlank() && questions.isEmpty() && differences.isEmpty()) return

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            "NARROW IT DOWN",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))

        if (fresh && guide.view.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "If it is still there, photograph ${guide.view}.",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "That is where the difference is. A second photograph of the same " +
                            "individual is added to the evidence rather than replacing it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onAddPhoto) { Text("Add that photograph") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        questions.forEach { character ->
            Text(character.prompt, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            character.options.forEach { option ->
                val chosen = answered[character.key] == option.label
                Surface(
                    color = if (chosen) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable { onAnswer(character, option.label) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (chosen) {
                            Spacer(Modifier.width(8.dp))
                            Text("✓", color = Warm.Rust)
                        }
                    }
                }
            }
            // The caveat, where the source gives one. A key that hides its own unreliability
            // is how somebody ends up certain and wrong.
            if (character.note.isNotBlank()) {
                Text(
                    character.note,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            if (character.source.isNotBlank()) {
                Text(
                    character.source.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
        }

        if (differences.isNotEmpty()) {
            Text(
                if (questions.isEmpty()) "How these are told apart" else "Also said about them",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            differences.forEach { row ->
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                row.says.forEach { sentence ->
                    Text(
                        "“$sentence”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    row.source.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * Assemble a narrowing for the answer on screen, or nothing.
 *
 * Nothing is the common case and must stay cheap: the answer already reached species, or no
 * guide was written for this branch, or every question it holds has been answered. A screen
 * that offers an empty section is worse than one that offers none.
 */
fun narrowingFor(
    answer: dk.lifelist.core.Answer,
    taxonomy: dk.lifelist.core.Taxonomy?,
    assets: CharacterAssets,
    answered: Map<String, String>,
    fresh: Boolean,
    onAnswer: (Characters.Character, String) -> Unit,
): Narrowing? {
    if (taxonomy == null) return null
    // Only when the determination stopped short. A species-level answer has nothing to narrow,
    // and offering to narrow it would be the app arguing with itself.
    val node = taxonomy.nodeOrNull(answer.taxonId) ?: return null
    if (node.isLeaf) return null
    val lineage = runCatching { taxonomy.lineage(answer.taxonId) }.getOrDefault(emptyList())
    val (taxonId, raw) = assets.forLineage(lineage) ?: return null
    val candidates = answer.candidates.map { it.taxonId }
    val guide = Narrow.guideOf(raw, taxonId)
    val unanswered = guide.copy(
        characters = guide.applicable(candidates).filter { it.key !in answered },
    )
    if (unanswered.characters.isEmpty() && raw.differences.isEmpty() && raw.view.isBlank()) {
        return null
    }
    return Narrowing(
        guide = unanswered,
        differences = raw.differences.filter { it.taxon_id in candidates.toSet() },
        fresh = fresh,
        candidates = candidates,
        answered = answered,
        onAnswer = onAnswer,
    )
}
