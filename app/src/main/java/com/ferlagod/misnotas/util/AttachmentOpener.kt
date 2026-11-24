// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Abre un fichero adjunto con la app que el usuario elija. Se copia a la caché con su nombre
 * original (para que la otra app lo reconozca) y se comparte solo con permiso de lectura.
 * Las copias antiguas se borran al abrir otra.
 */
object AttachmentOpener {

    private const val DIR = "open"

    suspend fun open(context: Context, id: String, name: String?) {
        val source = AttachmentStore.fileFor(context, id)
        if (!source.exists() || source.length() == 0L) {
            Toast.makeText(context, LanguageManager.localizedContext(context).getString(R.string.attachment_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val mime = withContext(Dispatchers.IO) { AppDatabase.getInstance(context).attachmentDao().getById(id)?.mimeType }
            ?: name?.let { AttachmentStore.mimeForName(it) }
            ?: "application/octet-stream"
        val fileName = (name?.takeIf { it.isNotBlank() } ?: "adjunto${AttachmentStore.extensionForMime(mime)}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val copy = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR)
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val sub = File(dir, id).apply { mkdirs() }
            File(sub, fileName).also { source.copyTo(it, overwrite = true) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, LanguageManager.localizedContext(context).getString(R.string.attachment_no_app), Toast.LENGTH_SHORT).show()
        }
    }
}
