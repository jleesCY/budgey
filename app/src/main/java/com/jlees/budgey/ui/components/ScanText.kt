package com.jlees.budgey.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** One block of text a scan produced, e.g. ("Google's text reader", "TOTAL 23.45 …"). */
data class ScanTextSection(val title: String, val text: String)

/**
 * Shows exactly what the scanner read from the picture — the text reader's rows and, when an
 * AI model was used, its answer — so you can see why a field came out the way it did.
 * The text is selectable for copying.
 */
@Composable
fun ScanTextDialog(sections: List<ScanTextSection>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
        title = { Text("What the scan read") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val shown = sections.filter { it.text.isNotBlank() }
                if (shown.isEmpty()) Text("No text was found in this picture.")
                shown.forEach { s ->
                    Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer {
                        Text(s.text.trim(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
