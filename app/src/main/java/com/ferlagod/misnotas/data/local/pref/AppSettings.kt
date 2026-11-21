// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.pref

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tema de la interfaz. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Orden de las notas en las listas (las fijadas siempre van primero). */
enum class SortOrder { MODIFIED_DESC, MODIFIED_ASC, TITLE_ASC, TITLE_DESC }

/** Avisos de ayuda que se muestran una sola vez (hasta que el usuario los cierra). */
object Hints {
    /** En el editor: las notas se abren en modo lectura y el lápiz pasa a edición. */
    const val READ_MODE = "read_mode"
    /** En la lista de notas: mantener pulsada una nota para seleccionar varias. */
    const val MULTI_SELECT = "multi_select"
    /** En el inicio, sin Nextcloud: las notas solo están en este dispositivo. */
    const val LOCAL_MODE = "local_mode"
}

/** Preferencias de la app que no son secretas (aspecto y comportamiento). */
data class AppSettingsState(
    val gridLayout: Boolean = true,
    val sortOrder: SortOrder = SortOrder.MODIFIED_DESC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** En modo oscuro, negro puro en lugar de gris (pantallas OLED). */
    val amoled: Boolean = false,
    val autoPurgeTrash: Boolean = true,
    val appLock: Boolean = false,
    /** Copia de seguridad automática diaria en [autoBackupFolder]. */
    val autoBackup: Boolean = false,
    /** Carpeta elegida por el usuario (URI de árbol SAF con permiso persistente). */
    val autoBackupFolder: String? = null,
    /** Momento de la última copia automática correcta (0 si nunca). */
    val lastAutoBackup: Long = 0L,
    /** La última copia automática falló (p. ej. la carpeta ya no existe). */
    val autoBackupFailed: Boolean = false,
    /** Ya se decidió si crear la nota de bienvenida (solo se intenta en el primer arranque). */
    val welcomeSeeded: Boolean = false,
    /** Avisos de [Hints] que el usuario ya ha cerrado. */
    val dismissedHints: Set<String> = emptySet()
) {
    fun showHint(key: String) = key !in dismissedHints
}

/**
 * Preferencias no sensibles en SharedPreferences normales, expuestas como [StateFlow]
 * para que la interfaz reaccione al instante. Comparte fichero con el idioma.
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    private fun read() = AppSettingsState(
        gridLayout = prefs.getBoolean(KEY_GRID, true),
        sortOrder = enumOrDefault(prefs.getString(KEY_SORT, null), SortOrder.MODIFIED_DESC),
        themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        amoled = prefs.getBoolean(KEY_AMOLED, false),
        autoPurgeTrash = prefs.getBoolean(KEY_AUTO_PURGE, true),
        appLock = prefs.getBoolean(KEY_APP_LOCK, false),
        autoBackup = prefs.getBoolean(KEY_AUTO_BACKUP, false),
        autoBackupFolder = prefs.getString(KEY_AUTO_BACKUP_FOLDER, null),
        lastAutoBackup = prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L),
        autoBackupFailed = prefs.getBoolean(KEY_AUTO_BACKUP_FAILED, false),
        welcomeSeeded = prefs.getBoolean(KEY_WELCOME_SEEDED, false),
        dismissedHints = prefs.getStringSet(KEY_DISMISSED_HINTS, null)?.toSet().orEmpty()
    )

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        _state.value = read()
    }

    fun setGridLayout(grid: Boolean) = update { putBoolean(KEY_GRID, grid) }
    fun setSortOrder(order: SortOrder) = update { putString(KEY_SORT, order.name) }
    fun setThemeMode(mode: ThemeMode) = update { putString(KEY_THEME, mode.name) }
    fun setAmoled(enabled: Boolean) = update { putBoolean(KEY_AMOLED, enabled) }
    fun setAutoPurgeTrash(enabled: Boolean) = update { putBoolean(KEY_AUTO_PURGE, enabled) }
    fun setAppLock(enabled: Boolean) = update { putBoolean(KEY_APP_LOCK, enabled) }
    fun setAutoBackup(enabled: Boolean) = update { putBoolean(KEY_AUTO_BACKUP, enabled) }
    fun setAutoBackupFolder(uri: String?) = update {
        putString(KEY_AUTO_BACKUP_FOLDER, uri)
        putBoolean(KEY_AUTO_BACKUP_FAILED, false)
    }
    fun setAutoBackupResult(success: Boolean, time: Long = System.currentTimeMillis()) = update {
        if (success) putLong(KEY_LAST_AUTO_BACKUP, time)
        putBoolean(KEY_AUTO_BACKUP_FAILED, !success)
    }

    fun setWelcomeSeeded() = update { putBoolean(KEY_WELCOME_SEEDED, true) }
    fun dismissHint(key: String) {
        if (key in _state.value.dismissedHints) return
        // Se guarda un conjunto nuevo: SharedPreferences no admite modificar el que devuelve.
        update { putStringSet(KEY_DISMISSED_HINTS, _state.value.dismissedHints + key) }
    }

    companion object {
        private const val PREFS = "misnotas_settings"
        private const val KEY_GRID = "grid_layout"
        private const val KEY_SORT = "sort_order"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_AUTO_PURGE = "auto_purge_trash"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_AUTO_BACKUP = "auto_backup"
        private const val KEY_AUTO_BACKUP_FOLDER = "auto_backup_folder"
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup"
        private const val KEY_AUTO_BACKUP_FAILED = "auto_backup_failed"
        private const val KEY_WELCOME_SEEDED = "welcome_seeded"
        private const val KEY_DISMISSED_HINTS = "dismissed_hints"

        /** Días que una nota permanece en la papelera antes del vaciado automático. */
        const val TRASH_RETENTION_DAYS = 30

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(context).also { INSTANCE = it }
            }

        private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
