// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.ui.markdown.markdownPreview
import com.ferlagod.misnotas.util.TextSearch

/** Elegir una nota de una lista con buscador (enlazar una nota o usar una plantilla). */
@Composable
fun NotePickerDialog(title: String, notes: List<NoteEntity>, onPick: (NoteEntity) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val untitled = stringResource(R.string.untitled)
    val shown = remember(notes, query) {
        val terms = TextSearch.terms(query)
        notes.filter { n -> terms.all { TextSearch.normalize(n.title).contains(it) } }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_notes_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { note ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(note) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(note.title.ifBlank { untitled }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val preview = markdownPreview(note.content, maxLines = 1).trim()
                            if (preview.isNotBlank()) {
                                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
