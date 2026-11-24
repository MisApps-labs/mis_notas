// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.ferlagod.misnotas.data.local.entity.NoteRevisionEntity
import com.ferlagod.misnotas.data.local.entity.NoteSyncBaseEntity
import kotlinx.coroutines.flow.Flow

/** Historial de versiones y bases de fusión de las notas. */
@Dao
interface NoteHistoryDao {

    @Insert
    suspend fun insertRevision(revision: NoteRevisionEntity): Long

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun revisionsFlow(noteId: String): Flow<List<NoteRevisionEntity>>

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestRevision(noteId: String): NoteRevisionEntity?

    @Query("SELECT * FROM note_revisions WHERE id = :id")
    suspend fun getRevision(id: Long): NoteRevisionEntity?

    @Query("DELETE FROM note_revisions WHERE id = :id")
    suspend fun deleteRevision(id: Long)

    /** Deja como mucho [keep] versiones por nota y borra las anteriores a [before]. */
    @Query(
        "DELETE FROM note_revisions WHERE createdAt < :before OR id IN (" +
            "SELECT r.id FROM note_revisions r WHERE (" +
            "SELECT COUNT(*) FROM note_revisions n WHERE n.noteId = r.noteId AND n.createdAt > r.createdAt) >= :keep)"
    )
    suspend fun prune(keep: Int, before: Long)

    /** Borra historial y bases de notas que ya no existen. */
    @Query("DELETE FROM note_revisions WHERE noteId NOT IN (SELECT id FROM notes)")
    suspend fun deleteOrphanRevisions()

    @Query("DELETE FROM note_sync_base WHERE noteId NOT IN (SELECT id FROM notes)")
    suspend fun deleteOrphanBases()

    @Query("SELECT * FROM note_sync_base WHERE noteId = :noteId")
    suspend fun getBase(noteId: String): NoteSyncBaseEntity?

    @Upsert
    suspend fun upsertBase(base: NoteSyncBaseEntity)

    @Query("DELETE FROM note_sync_base")
    suspend fun clearBases()
}
