// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Último texto de la nota que coincidía en el dispositivo y en el servidor. Es la "base" de la
 * fusión a tres bandas: si la nota cambia a la vez en dos sitios, se comparan ambos cambios con
 * ella y, si no se pisan, se combinan sin crear una copia "(conflicto)".
 */
@Entity(tableName = "note_sync_base")
data class NoteSyncBaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "noteId")
    val noteId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String
)
