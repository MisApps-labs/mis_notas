// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ferlagod.misnotas.data.local.entity.RemoteRevEntity

@Dao
interface RemoteRevDao {

    @Query("SELECT * FROM remote_revs")
    suspend fun getAll(): List<RemoteRevEntity>

    @Upsert
    suspend fun upsertAll(revs: List<RemoteRevEntity>)

    @Query("DELETE FROM remote_revs")
    suspend fun clear()
}
