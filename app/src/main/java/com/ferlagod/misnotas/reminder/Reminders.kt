// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ferlagod.misnotas.MainActivity
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.ReminderEntity
import com.ferlagod.misnotas.data.local.pref.AppSettings
import com.ferlagod.misnotas.data.local.pref.AuthPreferences
import com.ferlagod.misnotas.ui.markdown.markdownPreview
import com.ferlagod.misnotas.util.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recordatorios de notas con AlarmManager.
 *
 * Se usan alarmas "inexactas pero permitidas en reposo" (setAndAllowWhileIdle): no requieren el
 * permiso de alarmas exactas y, en la práctica, avisan con pocos minutos de margen.
 */
object Reminders {

    private const val CHANNEL_ID = "reminders"
    private const val EXTRA_NOTE_ID = "note_id"

    suspend fun set(context: Context, noteId: String, triggerAt: Long) {
        AppDatabase.getInstance(context).reminderDao().upsert(ReminderEntity(noteId, triggerAt))
        schedule(context, noteId, triggerAt)
    }

    suspend fun clear(context: Context, noteId: String) {
        AppDatabase.getInstance(context).reminderDao().delete(noteId)
        alarmManager(context).cancel(pendingIntent(context, noteId))
    }

    /** Vuelve a programar todos (tras reiniciar o actualizar) y quita los de notas borradas. */
    suspend fun rescheduleAll(context: Context) {
        val dao = AppDatabase.getInstance(context).reminderDao()
        for (id in dao.orphanIds()) clear(context, id)
        for (r in dao.getAll()) schedule(context, r.noteId, r.triggerAt)
    }

    private fun schedule(context: Context, noteId: String, triggerAt: Long) {
        // Uno vencido (p. ej. con el móvil apagado) avisa enseguida.
        val at = maxOf(triggerAt, System.currentTimeMillis() + 1_000)
        alarmManager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, noteId))
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context, noteId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_NOTE_ID, noteId)
            .setData("misnotas://reminder/$noteId".toUri())
        return PendingIntent.getBroadcast(context, noteId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal suspend fun fire(context: Context, noteId: String) {
        val db = AppDatabase.getInstance(context)
        val reminder = db.reminderDao().get(noteId) ?: return
        if (reminder.triggerAt > System.currentTimeMillis() + 60_000) {
            // Se cambió la hora del recordatorio o del sistema: se vuelve a programar.
            schedule(context, noteId, reminder.triggerAt)
            return
        }
        db.reminderDao().delete(noteId)
        val note = db.noteDao().getNoteById(noteId)
        if (note == null || note.isDeleted) return
        notify(context, noteId, note.title, note.content)
    }

    private fun notify(context: Context, noteId: String, title: String, content: String) {
        if (!canNotify(context)) return
        val res = LanguageManager.localizedContext(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, res.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // Con el bloqueo activado o la cuenta bloqueada, el aviso no enseña el contenido.
        val auth = AuthPreferences.getInstance(context)
        val hidden = AppSettings.getInstance(context).state.value.appLock || (auth.hasCredentials() && !auth.isKeyUnlocked())
        val shownTitle = if (hidden) res.getString(R.string.reminder_private_title) else title.ifBlank { res.getString(R.string.untitled) }
        val text = if (hidden) res.getString(R.string.reminder_private_text) else markdownPreview(content, maxLines = 3).trim()
        val open = PendingIntent.getActivity(
            context, noteId.hashCode(), MainActivity.openNoteIntent(context, noteId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(if (hidden) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(noteId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permiso retirado entre la comprobación y el aviso.
        }
    }

    internal fun noteIdOf(intent: Intent): String? = intent.getStringExtra(EXTRA_NOTE_ID)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = Reminders.noteIdOf(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.fire(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

private val HANDLED = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIMEZONE_CHANGED,
    Intent.ACTION_TIME_CHANGED
)
