// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.pref.AppSettings
import com.ferlagod.misnotas.util.LanguageManager
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Copia de seguridad automática cada 24 h en la carpeta que eligió el usuario.
 * Se guardan como máximo [BackupManager.AUTO_BACKUP_KEEP] copias: al crear una nueva se
 * borra la más antigua. No necesita red ni la bóveda desbloqueada (la base local no va cifrada).
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = AppSettings.getInstance(applicationContext)
        val state = settings.state.value
        val folder = state.autoBackupFolder
        if (!state.autoBackup || folder == null) return Result.success()

        return try {
            val untitled = LanguageManager.localizedContext(applicationContext).getString(R.string.untitled)
            BackupManager(applicationContext).exportAutoBackup(folder.toUri(), untitled)
            settings.setAutoBackupResult(success = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Carpeta borrada, permiso revocado, sin espacio… Se reintenta en la siguiente ejecución.
            settings.setAutoBackupResult(success = false)
            Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "misnotas-auto-backup"

        /**
         * Programa la copia diaria. Con [replace] la primera se hace enseguida (al activar la
         * opción o cambiar de carpeta); sin él se mantiene la programación existente.
         */
        fun schedule(context: Context, replace: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
