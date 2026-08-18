package dk.lifelist.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How sure the app has to be before it commits to a rank.
 *
 * It used to be a bare slider stapled to the bottom of the result screen, below the buttons,
 * where it read as a debug control. It is not a debug control — it is the one setting that
 * changes what the app is willing to claim, and spec §4.4 makes it a display-time decision,
 * so old records re-render honestly instead of being rewritten. A bottom sheet off the top
 * bar is where Android puts a setting like that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdSheet(
    threshold: Float,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("How sure before it commits", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Below this, the app answers at a coarser rank instead of guessing a species. " +
                    "Raise it and you will see more genus and family answers, and fewer wrong ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "${Math.round(threshold * 100)}%",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // Spec §4.4 — 0.50 to 0.95.
            Slider(value = threshold, onValueChange = onChange, valueRange = 0.50f..0.95f)
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "50% · more species, more mistakes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "95% · cautious",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
