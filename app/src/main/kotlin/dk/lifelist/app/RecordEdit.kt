package dk.lifelist.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.lifelist.core.Record
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Editing the parts of a sighting the app got wrong or never knew.
 *
 * Deliberately *not* the determination: that has its own three routes — settle, correct,
 * keep it broader — each of which keeps what the model said alongside your answer. This screen
 * is for the three facts nothing else can fix. The date, because a photograph imported later
 * is dated when it was kept rather than when it was taken. The place, because a reverse
 * geocode says "Frederiksberg" when you were in a specific hedge. And notes, because a field
 * guide has margins for a reason.
 *
 * The coordinates are not editable, and that is on purpose. A typed place name is a label
 * you chose; a latitude is a claim about where you were. Letting the second be edited by hand
 * turns the one field with provenance into one without, and the record has just learned to say
 * where its coordinates came from (§46).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditContent(
    record: Record,
    onSave: (Record) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var place by remember(record.id) { mutableStateOf(record.place.orEmpty()) }
    var notes by remember(record.id) { mutableStateOf(record.notes.orEmpty()) }
    var observedAt by remember(record.id) { mutableStateOf(record.observedAt) }
    var picking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Edit sighting",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "When",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { picking = true }) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(dayOf(observedAt))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "The time of day is kept as it was — only the date moves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = place,
            onValueChange = { place = it },
            label = { Text("Where") },
            placeholder = { Text("The hedge by the stream") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            placeholder = { Text("On umbellifers, barely moving in the cold") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = {
                    onSave(
                        record.copy(
                            observedAt = observedAt,
                            // Blank means "no place", not an empty string sitting in the file
                            // and rendering as a stray gap on the record.
                            place = place.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = null,
                Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(7.dp))
            Text("Delete this sighting", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
    }

    if (picking) {
        // Seeded in UTC because that is the only zone the picker speaks; `withDate` puts the
        // chosen day back into the phone's zone, keeping the original time of day.
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcNoon(observedAt))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { observedAt = withDate(observedAt, it) }
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this sighting?") },
            text = {
                Text(
                    "The record and its photographs go. Anything already saved to your camera " +
                        "roll stays there."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
        )
    }
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.UK).format(Date(millis))

/**
 * The record's day as UTC noon.
 *
 * Noon rather than midnight: the picker works in UTC, and a midnight seeded from a zone behind
 * UTC lands on the previous day, so opening the picker on a record would show yesterday.
 */
internal fun toUtcNoon(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
            12,
            0,
        )
    }.timeInMillis
}

/** The day from `chosenUtc`, at the time of day `original` already had, in the phone's zone. */
internal fun withDate(original: Long, chosenUtc: Long): Long {
    val chosen = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = chosenUtc
    }
    return Calendar.getInstance().apply {
        timeInMillis = original
        set(Calendar.YEAR, chosen.get(Calendar.YEAR))
        set(Calendar.MONTH, chosen.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, chosen.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
