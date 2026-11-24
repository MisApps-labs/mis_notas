// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.entity.NoteRevisionEntity
import com.ferlagod.misnotas.ui.markdown.MarkdownReader
import com.ferlagod.misnotas.ui.markdown.markdownPreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historial de versiones de una nota: lista de versiones guardadas en este dispositivo, vista
 * previa y "Restaurar" (la versión actual se guarda antes en el historial, así que se puede deshacer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDialog(
    revisions: List<NoteRevisionEntity>,
    onRestore: (NoteRevisionEntity) -> Unit,
    onDelete: (NoteRevisionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<NoteRevisionEntity?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.history_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        val current = selected
                        IconButton(onClick = { if (current != null) selected = null else onDismiss() }) {
                            Icon(
                                if (current != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        selected?.let { rev ->
                            IconButton(onClick = { onDelete(rev); selected = null }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val rev = selected
            if (rev == null) {
                if (revisions.isEmpty()) {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(padding).padding(24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        item {
                            Text(
                                stringResource(R.string.history_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(revisions, key = { it.id }) { r ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = r }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(formatTime(r.createdAt), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                    Text(
                                        stringResource(reasonLabel(NoteRevisionEntity.Reason.fromKey(r.reason))),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (r.title.isNotBlank()) {
                                    Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    markdownPreview(r.content, maxLines = 2),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(formatTime(rev.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (rev.title.isNotBlank()) {
                        Text(rev.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(12.dp))
                    }
                    MarkdownReader(markdownText = rev.content, primaryBlue = MaterialTheme.colorScheme.primary, onToggleChecklist = {})
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { onRestore(rev) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.history_restore))
                    }
                }
            }
        }
    }
}

private fun reasonLabel(reason: NoteRevisionEntity.Reason): Int = when (reason) {
    NoteRevisionEntity.Reason.EDIT -> R.string.history_reason_edit
    NoteRevisionEntity.Reason.BEFORE_SYNC -> R.string.history_reason_sync
    NoteRevisionEntity.Reason.BEFORE_MERGE -> R.string.history_reason_merge
    NoteRevisionEntity.Reason.BEFORE_RESTORE -> R.string.history_reason_restore
}

private fun formatTime(time: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMyyyyjmm"), locale).format(Date(time))
}
