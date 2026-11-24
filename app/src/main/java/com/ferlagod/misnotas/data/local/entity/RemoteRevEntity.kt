// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Versión (`rev`) más alta que se conoce de cada fichero remoto en una bóveda v3. Si el
 * servidor devuelve un fichero con un `rev` menor, es una vuelta atrás (rollback).
 */
@Entity(tableName = "remote_revs")
data class RemoteRevEntity(
    @PrimaryKey
    @ColumnInfo(name = "fileName")
    val fileName: String,

    @ColumnInfo(name = "rev")
    val rev: Long
)
