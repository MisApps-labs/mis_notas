// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.sync

import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {

    private fun note(
        title: String = "t",
        content: String = "c",
        updatedAt: Long = 100L,
        status: SyncStatus = SyncStatus.SYNCED,
        etag: String? = "e1"
    ) = NoteEntity(id = "n1", title = title, content = content, updatedAt = updatedAt, syncStatus = status, remoteEtag = etag)

    private fun live(n: NoteEntity) = RemoteRecord.Live(n.copy(syncStatus = SyncStatus.SYNCED, remoteEtag = null))
    private val newId = { "copia" }

    @Test
    fun newRemoteNoteIsSaved() {
        val action = SyncMerger.resolveNote(null, live(note()), "e2", newId)
        assertEquals(MergeAction.SaveRemote(note(etag = "e2")), action)
    }

    @Test
    fun syncedLocalTakesRemoteChanges() {
        val action = SyncMerger.resolveNote(note(), live(note(content = "nuevo", updatedAt = 200)), "e2", newId)
        assertEquals(MergeAction.SaveRemote(note(content = "nuevo", updatedAt = 200, etag = "e2")), action)
    }

    @Test
    fun syncedLocal_olderRemoteIsNotAppliedSilently() {
        val local = note(content = "actual", updatedAt = 200)
        val rollback = SyncMerger.resolveNote(local, live(note(content = "viejo", updatedAt = 100)), "e2", newId) as MergeAction.Conflict
        assertEquals("actual", rollback.main.content)
        assertEquals(SyncStatus.PENDING_UPLOAD, rollback.main.syncStatus)
        assertEquals("e2", rollback.main.remoteEtag)
        assertEquals("viejo", rollback.copy.content)

        val sameText = SyncMerger.resolveNote(local, live(note(content = "actual", updatedAt = 100).copy(isPinned = true)), "e2", newId)
        assertEquals(MergeAction.Reupload("e2"), sameText)

        val folder = FolderEntity(id = "f", name = "actual", updatedAt = 200, syncStatus = SyncStatus.SYNCED, remoteEtag = "e1")
        val oldFolder = RemoteRecord.Live(FolderEntity(id = "f", name = "vieja", updatedAt = 100))
        assertEquals(MergeAction.Reupload("e2"), SyncMerger.resolveFolder(folder, oldFolder, "e2"))
    }

    @Test
    fun identicalPendingChangesOnlyUpdateEtag() {
        val local = note(status = SyncStatus.PENDING_UPLOAD, etag = null)
        assertEquals(MergeAction.KeepLocal("e2"), SyncMerger.resolveNote(local, live(note()), "e2", newId))
    }

    @Test
    fun concurrentEditsKeepBothVersions_remoteNewer() {
        val local = note(content = "local", updatedAt = 100, status = SyncStatus.PENDING_UPLOAD)
        val remote = note(content = "remoto", updatedAt = 200)
        val action = SyncMerger.resolveNote(local, live(remote), "e2", newId) as MergeAction.Conflict
        assertEquals("remoto", action.main.content)
        assertEquals(SyncStatus.SYNCED, action.main.syncStatus)
        assertEquals("local", action.copy.content)
        assertEquals("copia", action.copy.id)
        assertTrue(action.copy.title.endsWith(SyncMerger.CONFLICT_SUFFIX))
        assertEquals(SyncStatus.PENDING_UPLOAD, action.copy.syncStatus)
    }

    @Test
    fun concurrentEditsKeepBothVersions_localNewer() {
        val local = note(content = "local", updatedAt = 300, status = SyncStatus.PENDING_UPLOAD)
        val remote = note(content = "remoto", updatedAt = 200)
        val action = SyncMerger.resolveNote(local, live(remote), "e2", newId) as MergeAction.Conflict
        assertEquals("local", action.main.content)
        assertEquals("e2", action.main.remoteEtag)
        assertEquals(SyncStatus.PENDING_UPLOAD, action.main.syncStatus)
        assertEquals("remoto", action.copy.content)
    }

    @Test
    fun verifiedTombstoneDeletesSyncedNote() {
        assertEquals(
            MergeAction.DeleteLocal,
            SyncMerger.resolveNote(note(), RemoteRecord.Tombstone("n1", 1, verified = true), "e2", newId)
        )
    }

    @Test
    fun unverifiedTombstoneOnlyMovesToTrash() {
        assertEquals(
            MergeAction.TrashLocal("e2"),
            SyncMerger.resolveNote(note(), RemoteRecord.Tombstone("n1", 1, verified = false), "e2", newId)
        )
    }

    @Test
    fun missingRemoteNoteGoesToTrashInsteadOfBeingDeleted() {
        assertEquals(MissingRemoteAction.TRASH_AND_REUPLOAD, SyncMerger.resolveMissingRemoteNote(SyncStatus.SYNCED, "e1"))
        assertEquals(MissingRemoteAction.DELETE_LOCAL, SyncMerger.resolveMissingRemoteNote(SyncStatus.PENDING_DELETE, "e1"))
        assertEquals(MissingRemoteAction.MARK_FOR_UPLOAD, SyncMerger.resolveMissingRemoteNote(SyncStatus.SYNCED, null))
    }

    @Test
    fun tombstoneNeverDeletesUnsyncedEdits() {
        val local = note(status = SyncStatus.PENDING_UPLOAD)
        assertEquals(MergeAction.KeepLocal("e2"), SyncMerger.resolveNote(local, RemoteRecord.Tombstone("n1", 1), "e2", newId))
        assertEquals(
            MergeAction.KeepLocal("e2"),
            SyncMerger.resolveNote(local, RemoteRecord.Tombstone("n1", 1, verified = true), "e2", newId)
        )
    }

    @Test
    fun remoteEditAfterLocalPurgeResurrectsNote() {
        val local = note(updatedAt = 100, status = SyncStatus.PENDING_DELETE)
        val action = SyncMerger.resolveNote(local, live(note(updatedAt = 200)), "e2", newId)
        assertTrue(action is MergeAction.SaveRemote)
    }

    @Test
    fun missingRemoteRules() {
        assertEquals(MissingRemoteAction.DELETE_LOCAL, SyncMerger.resolveMissingRemote(SyncStatus.SYNCED, "e1"))
        assertEquals(MissingRemoteAction.MARK_FOR_UPLOAD, SyncMerger.resolveMissingRemote(SyncStatus.SYNCED, null))
        assertEquals(MissingRemoteAction.NOTHING, SyncMerger.resolveMissingRemote(SyncStatus.PENDING_UPLOAD, null))
        assertEquals(MissingRemoteAction.FORGET_ETAG, SyncMerger.resolveMissingRemote(SyncStatus.PENDING_UPLOAD, "e1"))
        assertEquals(MissingRemoteAction.DELETE_LOCAL, SyncMerger.resolveMissingRemote(SyncStatus.PENDING_DELETE, null))
    }

    @Test
    fun legacyNote_neverReplacesLocal() {
        val local = note(status = SyncStatus.SYNCED, etag = "e1")
        val sameText = local.copy(isDeleted = true, updatedAt = Long.MAX_VALUE)
        assertEquals(MergeAction.Reupload("e2"), SyncMerger.resolveLegacyNote(local, sameText, "e2", newId))

        val otherText = local.copy(content = "texto viejo", updatedAt = Long.MAX_VALUE)
        val action = SyncMerger.resolveLegacyNote(local, otherText, "e2", newId) as MergeAction.Conflict
        assertEquals(local.content, action.main.content)
        assertEquals(SyncStatus.PENDING_UPLOAD, action.main.syncStatus)
        assertEquals("texto viejo", action.copy.content)

        val fresh = SyncMerger.resolveLegacyNote(null, otherText, "e2", newId) as MergeAction.SaveRemote
        assertEquals(SyncStatus.PENDING_UPLOAD, fresh.entity.syncStatus)
    }

    @Test
    fun folderTombstones_onlyVerifiedOnesDelete() {
        val synced = FolderEntity(id = "f", name = "carpeta", updatedAt = 100, syncStatus = SyncStatus.SYNCED, remoteEtag = "e1")
        assertEquals(MergeAction.DeleteLocal, SyncMerger.resolveFolder(synced, RemoteRecord.Tombstone("f", 1, verified = true), "e2"))
        assertEquals(MergeAction.Reupload("e2"), SyncMerger.resolveFolder(synced, RemoteRecord.Tombstone("f", 1, verified = false), "e2"))
        val pending = synced.copy(syncStatus = SyncStatus.PENDING_DELETE)
        assertEquals(MergeAction.DeleteLocal, SyncMerger.resolveFolder(pending, RemoteRecord.Tombstone("f", 1, verified = false), "e2"))
    }

    @Test
    fun missingRemoteFolder_isReuploadedNotDeleted() {
        assertEquals(MissingRemoteAction.REUPLOAD, SyncMerger.resolveMissingRemoteFolder(SyncStatus.SYNCED, "e1"))
        assertEquals(MissingRemoteAction.MARK_FOR_UPLOAD, SyncMerger.resolveMissingRemoteFolder(SyncStatus.SYNCED, null))
        assertEquals(MissingRemoteAction.DELETE_LOCAL, SyncMerger.resolveMissingRemoteFolder(SyncStatus.PENDING_DELETE, "e1"))
    }

    @Test
    fun folderLastWriteWins() {
        val local = FolderEntity(id = "f", name = "local", updatedAt = 100, syncStatus = SyncStatus.PENDING_UPLOAD)
        val newer = RemoteRecord.Live(FolderEntity(id = "f", name = "remota", updatedAt = 200))
        val older = RemoteRecord.Live(FolderEntity(id = "f", name = "remota", updatedAt = 50))
        assertTrue(SyncMerger.resolveFolder(local, newer, "e") is MergeAction.SaveRemote)
        assertEquals(MergeAction.KeepLocal("e"), SyncMerger.resolveFolder(local, older, "e"))
    }

    // --- Tras reconectar (resetSyncMetadata deja lo sincronizado como PENDING_UPLOAD sin ETag) ---

    @Test
    fun afterReconnect_verifiedTombstoneDoesNotDeleteLocalNote() {
        val local = note(status = SyncStatus.PENDING_UPLOAD, etag = null)
        val action = SyncMerger.resolveNote(local, RemoteRecord.Tombstone("n1", 1, verified = true), "e2", newId)
        assertEquals(MergeAction.KeepLocal("e2"), action)
    }

    @Test
    fun afterReconnect_differentRemoteVersionKeepsBoth() {
        val local = note(content = "local", updatedAt = 100, status = SyncStatus.PENDING_UPLOAD, etag = null)
        val action = SyncMerger.resolveNote(local, live(note(content = "remoto", updatedAt = 200)), "e2", newId)
        assertTrue(action is MergeAction.Conflict)
    }

    @Test
    fun afterReconnect_missingRemoteNoteIsKept() {
        assertEquals(MissingRemoteAction.NOTHING, SyncMerger.resolveMissingRemoteNote(SyncStatus.PENDING_UPLOAD, null))
    }

    // --- Freno de borrados masivos ---

    @Test
    fun massRemoval_fewNotesNeverBlock() {
        assertFalse(SyncMerger.isMassRemoval(removed = 1, live = 1))
        assertFalse(SyncMerger.isMassRemoval(removed = 4, live = 4))
    }

    @Test
    fun massRemoval_largeShareBlocks() {
        assertTrue(SyncMerger.isMassRemoval(removed = 114, live = 114))
        assertTrue(SyncMerger.isMassRemoval(removed = 5, live = 20))
    }

    @Test
    fun massRemoval_smallShareOfManyNotesIsAllowed() {
        assertFalse(SyncMerger.isMassRemoval(removed = 5, live = 21))
        assertFalse(SyncMerger.isMassRemoval(removed = 49, live = 1000))
    }

    @Test
    fun massRemoval_absoluteLimitAlwaysBlocks() {
        assertTrue(SyncMerger.isMassRemoval(removed = 50, live = 10_000))
    }

    // --- Fusión a tres bandas ---

    @Test
    fun concurrentEditsInDifferentLinesAreMerged() {
        val base = com.ferlagod.misnotas.data.local.entity.NoteSyncBaseEntity("n1", "Lista", "uno\ndos\ntres")
        val local = note(title = "Lista", content = "uno local\ndos\ntres", updatedAt = 200, status = SyncStatus.PENDING_UPLOAD, etag = "e1")
        val remote = note(title = "Lista", content = "uno\ndos\ntres remoto", updatedAt = 300)
        val action = SyncMerger.resolveNote(local, live(remote), "e2", { "copy" }, base = base)
        assertTrue(action is MergeAction.Merged)
        val merged = (action as MergeAction.Merged).entity
        assertEquals("uno local\ndos\ntres remoto", merged.content)
        assertEquals("e2", merged.remoteEtag)
        assertEquals(SyncStatus.PENDING_UPLOAD, merged.syncStatus)
    }

    @Test
    fun clashingEditsStillKeepBothVersions() {
        val base = com.ferlagod.misnotas.data.local.entity.NoteSyncBaseEntity("n1", "Lista", "uno")
        val local = note(title = "Lista", content = "uno A", updatedAt = 200, status = SyncStatus.PENDING_UPLOAD, etag = "e1")
        val remote = note(title = "Lista", content = "uno B", updatedAt = 300)
        assertTrue(SyncMerger.resolveNote(local, live(remote), "e2", { "copy" }, base = base) is MergeAction.Conflict)
    }

    @Test
    fun sameTextDifferentMetadataNeedsNoCopy() {
        val local = note(title = "T", content = "C", updatedAt = 200, status = SyncStatus.PENDING_UPLOAD, etag = "e1")
        val remote = note(title = "T", content = "C", updatedAt = 300).copy(isPinned = true, color = "BLUE")
        val action = SyncMerger.resolveNote(local, live(remote), "e2", { "copy" })
        assertTrue(action is MergeAction.Merged)
        val merged = (action as MergeAction.Merged).entity
        assertTrue(merged.isPinned)
        assertEquals("BLUE", merged.color)
    }
}
