// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity as startIntent
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ferlagod.misnotas.MainActivity
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.pref.AppSettings
import com.ferlagod.misnotas.data.local.pref.AuthPreferences
import com.ferlagod.misnotas.ui.markdown.markdownPreview
import com.ferlagod.misnotas.util.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Widget de la pantalla de inicio: botón de nota nueva y las notas fijadas y recientes.
 *
 * Privacidad: si el bloqueo de la app está activado o la cuenta está bloqueada con la contraseña
 * maestra, el widget no enseña ningún título ni texto, solo el botón de nota nueva.
 */
class NotesWidget : GlanceAppWidget() {

    private data class Item(val id: String, val title: String, val preview: String, val pinned: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val res = LanguageManager.localizedContext(context)
        val auth = AuthPreferences.getInstance(context)
        val hidden = AppSettings.getInstance(context).state.value.appLock || (auth.hasCredentials() && !auth.isKeyUnlocked())
        val untitled = res.getString(R.string.untitled)
        val items = if (hidden) emptyList() else AppDatabase.getInstance(context).noteDao().getWidgetNotes(MAX_ITEMS).map {
            Item(it.id, it.title.ifBlank { untitled }, markdownPreview(it.content, maxLines = 2).trim(), it.isPinned)
        }
        val labels = Labels(
            appName = res.getString(R.string.app_name),
            newNote = res.getString(R.string.cd_new_note),
            empty = res.getString(R.string.widget_empty),
            locked = res.getString(R.string.widget_locked)
        )
        provideContent {
            GlanceTheme { Content(context, items, hidden, labels) }
        }
    }

    private data class Labels(val appName: String, val newNote: String, val empty: String, val locked: String)

    @Composable
    private fun Content(context: Context, items: List<Item>, hidden: Boolean, labels: Labels) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp)
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labels.appName,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.primary),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(MainActivity::class.java))
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_add),
                    contentDescription = labels.newNote,
                    modifier = GlanceModifier
                        .size(36.dp)
                        .cornerRadius(18.dp)
                        .background(GlanceTheme.colors.primary)
                        .padding(8.dp)
                        .clickable(startIntent(MainActivity.newNoteIntent(context)))
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            when {
                hidden -> Message(labels.locked)
                items.isEmpty() -> Message(labels.empty)
                else -> LazyColumn {
                    items(items, itemId = { it.id.hashCode().toLong() }) { item ->
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .background(GlanceTheme.colors.surface)
                                    .cornerRadius(12.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .clickable(startIntent(MainActivity.openNoteIntent(context, item.id)))
                            ) {
                                Text(
                                    (if (item.pinned) "📌 " else "") + item.title,
                                    maxLines = 1,
                                    style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface)
                                )
                                if (item.preview.isNotBlank()) {
                                    Text(
                                        item.preview,
                                        maxLines = 2,
                                        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(
            text,
            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.padding(8.dp)
        )
    }

    companion object {
        private const val MAX_ITEMS = 12
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var pending: Job? = null

        /** Refresca los widgets (agrupando cambios seguidos para no redibujar con cada letra). */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch {
                    delay(1_500)
                    runCatching { NotesWidget().updateAll(app) }
                }
            }
        }
    }
}

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()
}
