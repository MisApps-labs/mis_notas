// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Recordatorio de una nota (uno por nota). Es local: avisa en el dispositivo donde se puso,
 * no se sincroniza, para no recibir el mismo aviso en todos los dispositivos.
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "noteId")
    val noteId: String,

    @ColumnInfo(name = "triggerAt")
    val triggerAt: Long
)
