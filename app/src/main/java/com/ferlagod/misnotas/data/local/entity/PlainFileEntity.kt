// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Modo sin cifrar: fichero `.md` del servidor que corresponde a cada nota ([path] relativo a la
 * carpeta, p. ej. `Trabajo/Plan.md`) y su ETag la última vez que coincidían.
 */
@Entity(tableName = "plain_files", indices = [Index(value = ["path"], unique = true)])
data class PlainFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "noteId")
    val noteId: String,

    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "etag")
    val etag: String?
)
