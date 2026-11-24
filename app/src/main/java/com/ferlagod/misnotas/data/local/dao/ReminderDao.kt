// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ferlagod.misnotas.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun get(noteId: String): ReminderEntity?

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    fun allFlow(): Flow<List<ReminderEntity>>

    @Query("DELETE FROM reminders WHERE noteId = :noteId")
    suspend fun delete(noteId: String)

    /** Recordatorios de notas que ya no existen o están en la papelera. */
    @Query("SELECT noteId FROM reminders WHERE noteId NOT IN (SELECT id FROM notes WHERE isDeleted = 0 AND syncStatus != 'PENDING_DELETE')")
    suspend fun orphanIds(): List<String>
}
