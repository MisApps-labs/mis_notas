// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ferlagod.misnotas.data.local.entity.PlainFileEntity

@Dao
interface PlainFileDao {

    @Query("SELECT * FROM plain_files")
    suspend fun getAll(): List<PlainFileEntity>

    @Query("SELECT * FROM plain_files WHERE noteId = :noteId")
    suspend fun get(noteId: String): PlainFileEntity?

    @Upsert
    suspend fun upsert(file: PlainFileEntity)

    @Query("DELETE FROM plain_files WHERE noteId = :noteId")
    suspend fun delete(noteId: String)

    @Query("DELETE FROM plain_files WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM plain_files")
    suspend fun clear()
}
