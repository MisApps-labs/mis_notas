// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Versión anterior de una nota (historial). Se guarda solo en este dispositivo.
 *
 * Se crea una al empezar a editar (como mucho cada pocos minutos), antes de que la
 * sincronización sustituya o fusione el texto local y antes de restaurar otra versión.
 */
@Entity(tableName = "note_revisions", indices = [Index(value = ["noteId", "createdAt"])])
data class NoteRevisionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "noteId")
    val noteId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    /** Por qué se guardó: ver [Reason]. */
    @ColumnInfo(name = "reason")
    val reason: String = Reason.EDIT.key
) {
    enum class Reason(val key: String) {
        EDIT("edit"),
        BEFORE_SYNC("sync"),
        BEFORE_MERGE("merge"),
        BEFORE_RESTORE("restore");

        companion object {
            fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: EDIT
        }
    }
}
