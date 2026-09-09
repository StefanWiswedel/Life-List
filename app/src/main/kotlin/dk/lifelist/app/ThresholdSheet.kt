package dk.lifelist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Certainty
import dk.lifelist.core.CertaintyTable
import kotlin.math.roundToInt

/**
 * How sure the app has to be before it commits to a rank.
 *
 * It used to be a bare slider stapled to the bottom of the result screen, below the buttons,
 * where it read as a debug control. It is not a debug control — it is the one setting that
 * changes what the app is willing to claim, and spec §4.4 makes it a display-time decision,
 * so old records re-render honestly instead of being rewritten.
 *
 * **[changed]** It is no longer a probability either. "I want the summed probability to clear
 * 0.70" is not a sentence anybody can hold an opinion about; "I want to be right 95% of the
 * time" is. The thresholds behind these choices were fitted per group on 40,595 held-out
 * photographs, so the same 95% is 0.70 for an insect and 0.82 for a bird — and for four groups
 * it is not available at all, which the sheet says out loud rather than papering over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdSheet(
    target: Float,
    table: CertaintyTable,
    /** The group the current answer belongs to, if there is a current answer. */
    certainty: Certainty?,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("How sure do you want to be?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Below this, the app answers at a coarser rank instead of guessing a species. " +
                    "Ask for more and you will see more genus and family answers, and fewer " +
                    "wrong ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            for (option in table.targets.ifEmpty { listOf(target) }) {
                TargetRow(
                    target = option,
                    selected = option == table.resolveTarget(target),
                    onSelect = { onChange(option) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (certainty != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    forThisAnswer(certainty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the setting means for the thing on screen right now.
 *
 * Three cases, and the two awkward ones are the reason this text exists. A target the model
 * cannot reach must not be reported as reached, and a group with too few test photographs to
 * measure must not have a number invented for it.
 */
internal fun forThisAnswer(certainty: Certainty): String {
    val delivered = certainty.delivered
    val group = certainty.group.lowercase()
    return when {
        delivered == null ->
            "There were too few test photographs of ${group} to measure this, so this one uses " +
                "the standard ${percent(certainty.threshold)} instead."
        certainty.reached ->
            "For ${group}, that means committing above ${percent(certainty.threshold)} — right " +
                "about ${percent(delivered)} of the time, measured on ${count(certainty.n)} " +
                "photographs it had never seen."
        else ->
            "${percent(certainty.target)} is out of reach for ${group}: at its best the model " +
                "is right ${percent(delivered)} of the time, on ${count(certainty.n)} " +
                "photographs. This is that best."
    }
}

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

private fun count(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

@Composable
private fun TargetRow(target: Float, selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            Text(
                "Right ${percent(target)} of the time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                blurb(target),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun blurb(target: Float): String = when {
    target <= 0.90f -> "More species named, and about one answer in ten wrong"
    target <= 0.95f -> "The balance most days — some answers stop at genus or family"
    else -> "Cautious. Many answers will stop above species, and a few groups cannot get here"
}
