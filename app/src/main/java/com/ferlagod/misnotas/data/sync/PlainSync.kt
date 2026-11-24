// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.data.backup.MarkdownFile
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.AttachmentEntity
import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.entity.NoteRevisionEntity
import com.ferlagod.misnotas.data.local.entity.NoteSyncBaseEntity
import com.ferlagod.misnotas.data.local.entity.PlainFileEntity
import com.ferlagod.misnotas.data.local.model.FolderTree
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.data.remote.webdav.RemoteFileMeta
import com.ferlagod.misnotas.data.remote.webdav.WebDavClient
import com.ferlagod.misnotas.data.remote.webdav.WebDavException
import com.ferlagod.misnotas.data.remote.webdav.WebDavPreconditionFailedException
import com.ferlagod.misnotas.util.TextMerge
import java.util.UUID

/**
 * Modo sin cifrar: cada nota es un fichero Markdown legible en la carpeta del servidor, como
 * hace la app Nextcloud Notes (`Notes/Carpeta/Título.md`). Se pueden leer y editar desde la web
 * de Nextcloud, Nextcloud Notes, Obsidian o cualquier editor.
 *
 * - Título = nombre del fichero; carpeta = directorio; contenido = el Markdown tal cual.
 * - Fijado, color y papelera son solo de este dispositivo (los ficheros no los guardan).
 * - Los adjuntos van en `.attachments/<id>.<ext>` y se enlazan con rutas relativas.
 * - Los renombrados y cambios de carpeta se suben con MOVE; un renombrado hecho en el servidor
 *   se reconoce porque el fichero conserva su ETag.
 * - Mismas reglas de seguridad que el modo cifrado: If-Match/If-None-Match, fusión a tres
 *   bandas, nada se borra sin pasar por la papelera y freno ante retiradas masivas.
 */
class PlainSync(
    private val db: AppDatabase,
    private val attachments: AttachmentStore,
    private val conflictSuffix: String,
    private val untitled: String
) {
    private val noteDao = db.noteDao()
    private val folderDao = db.folderDao()
    private val fileDao = db.plainFileDao()
    private val historyDao = db.noteHistoryDao()
    private val attachmentDao = db.attachmentDao()

    class Result(val rerun: Boolean, val warnings: Int, val protectedNotes: Int)

    private var rerun = false
    private var warnings = 0
    private var attachmentsDirReady = false

    companion object {
        private const val TAG = "PlainSync"
        const val ATTACHMENTS_DIR = ".attachments"
        private val NOTE_EXTENSIONS = setOf("md", "markdown", "txt")
        /** `../../.attachments/<id>.<ext>` en el Markdown del servidor. */
        private val REMOTE_ATTACHMENT = Regex("""(?:\.\./)*\.attachments/([A-Za-z0-9_-]+)\.[A-Za-z0-9]+""")
        private val LOCAL_ATTACHMENT = Regex("""attachment://([A-Za-z0-9_-]+)""")

        fun isNoteFile(path: String): Boolean =
            !path.startsWith("$ATTACHMENTS_DIR/") && path.substringAfterLast('.', "").lowercase() in NOTE_EXTENSIONS &&
                path.split('/').none { it.startsWith(".") }

        /** Título a partir del nombre del fichero. */
        fun titleOf(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')

        fun dirOf(path: String): List<String> = path.split('/').dropLast(1)
    }

    suspend fun round(client: WebDavClient): Result {
        rerun = false
        warnings = 0
        client.ensureRootFolder()
        val listing = client.listRecursive(includeHidden = setOf(ATTACHMENTS_DIR))
        val remoteFiles = listing.filter { isNoteFile(it.name) }.associateBy { it.name }
        val remoteAttachments = listing.filter { it.name.startsWith("$ATTACHMENTS_DIR/") }
            .associateBy { it.name.substringAfterLast('/').substringBeforeLast('.') }

        val folderCache = HashMap<String, String?>()
        val removals = mutableListOf<String>()
        pull(client, remoteFiles, remoteAttachments, folderCache, removals)

        var protectedNotes = 0
        if (removals.isNotEmpty()) {
            if (SyncMerger.isMassRemoval(removals.size, noteDao.countLive())) {
                // Seguramente la carpeta se ha vaciado o es otra: se conservan y se vuelven a subir.
                Log.w(TAG, "El servidor retiraría ${removals.size} notas a la vez; se conservan")
                removals.forEach { id ->
                    fileDao.delete(id)
                    noteDao.updateSyncStatus(id, SyncStatus.PENDING_UPLOAD)
                }
                protectedNotes = removals.size
            } else {
                removals.forEach { id ->
                    fileDao.delete(id)
                    noteDao.trashFromRemote(id, null)
                }
            }
        }

        push(client, remoteFiles, remoteAttachments)
        return Result(rerun, warnings, protectedNotes)
    }

    // ------------------------------------------------------------------
    // Descarga
    // ------------------------------------------------------------------

    private suspend fun pull(
        client: WebDavClient,
        remote: Map<String, RemoteFileMeta>,
        remoteAttachments: Map<String, RemoteFileMeta>,
        folderCache: HashMap<String, String?>,
        removals: MutableList<String>
    ) {
        val mappings = fileDao.getAll()
        val byPath = mappings.associateBy { it.path }
        val missing = mappings.filter { it.path !in remote }.toMutableList()
        val folders = folderDao.getAll().filter { !it.isDeleted }.associateBy { it.id }

        for ((path, meta) in remote) {
            val mapping = byPath[path]
            if (mapping != null && mapping.etag != null && mapping.etag == meta.etag) continue

            // Renombrado o movido en el servidor: mismo ETag en otra ruta.
            if (mapping == null) {
                val renamed = missing.firstOrNull { it.etag != null && it.etag == meta.etag }
                if (renamed != null) {
                    missing.remove(renamed)
                    fileDao.upsert(renamed.copy(path = path))
                    noteDao.getNoteById(renamed.noteId)?.let { local ->
                        if (local.syncStatus == SyncStatus.SYNCED) {
                            noteDao.upsertNote(
                                local.copy(title = titleOf(path), folderId = ensureFolders(dirOf(path), folderCache))
                            )
                        }
                    }
                    continue
                }
            }

            val body = try {
                client.downloadText(path)
            } catch (e: WebDavException) {
                if (e.code == 404) continue else throw e
            }
            val content = fromRemote(client, body, remoteAttachments)
            val folderId = ensureFolders(dirOf(path), folderCache)

            if (mapping == null) {
                // ¿Una nota local sin subir que iría justo a esta ruta? (p. ej. al reconectar)
                val candidate = noteDao.getAll().firstOrNull { n ->
                    !n.isDeleted && n.syncStatus != SyncStatus.PENDING_DELETE && fileDao.get(n.id) == null &&
                        desiredPath(n, folders) == path
                }
                if (candidate != null) {
                    if (candidate.content.trimEnd() != content.trimEnd()) {
                        // Mismo nombre pero distinto texto: la del servidor queda como copia.
                        insertRemoteNote(path, meta, content, folderId, asConflict = true)
                    }
                    fileDao.upsert(PlainFileEntity(candidate.id, path, meta.etag))
                    noteDao.updateSyncStatus(candidate.id, SyncStatus.PENDING_UPLOAD)
                    continue
                }
                insertRemoteNote(path, meta, content, folderId, asConflict = false)
                continue
            }

            db.withTransaction {
                val local = noteDao.getNoteById(mapping.noteId)
                val title = keepTitle(local, path)
                val remoteNote = (local ?: NoteEntity(id = mapping.noteId, title = title, content = "")).copy(
                    title = title,
                    content = content,
                    folderId = folderId,
                    updatedAt = maxOf(meta.lastModified, local?.updatedAt ?: 0L),
                    syncStatus = SyncStatus.SYNCED
                )
                when {
                    local == null || local.syncStatus == SyncStatus.SYNCED -> {
                        if (local != null && (local.title != remoteNote.title || local.content != remoteNote.content)) snapshot(local, NoteRevisionEntity.Reason.BEFORE_SYNC)
                        noteDao.upsertNote(remoteNote.copy(isDeleted = local?.isDeleted ?: false))
                    }
                    local.syncStatus == SyncStatus.PENDING_DELETE -> Unit
                    else -> {
                        val base = historyDao.getBase(local.id)
                        val mergedTitle = if (base != null) TextMerge.mergeLine(base.title, local.title, remoteNote.title) else null
                        val mergedContent = if (base != null) TextMerge.merge3(base.content, local.content, remoteNote.content) else null
                        when {
                            local.title == remoteNote.title && local.content == remoteNote.content -> Unit
                            mergedTitle != null && mergedContent != null -> {
                                snapshot(local, NoteRevisionEntity.Reason.BEFORE_MERGE)
                                noteDao.upsertNote(local.copy(title = mergedTitle, content = mergedContent))
                            }
                            else -> noteDao.upsertNote(
                                SyncMerger.conflictCopy(remoteNote, UUID.randomUUID().toString(), conflictSuffix, untitled)
                            )
                        }
                    }
                }
                historyDao.upsertBase(NoteSyncBaseEntity(mapping.noteId, remoteNote.title, remoteNote.content))
                fileDao.upsert(mapping.copy(etag = meta.etag))
            }
        }

        // Ficheros conocidos que ya no están en el servidor.
        for (m in missing) {
            val local = noteDao.getNoteById(m.noteId)
            when {
                local == null || local.isDeleted || local.syncStatus == SyncStatus.PENDING_DELETE -> fileDao.delete(m.noteId)
                local.syncStatus == SyncStatus.SYNCED -> removals += m.noteId
                else -> fileDao.delete(m.noteId) // Con cambios locales: se vuelve a crear.
            }
        }
    }

    private suspend fun insertRemoteNote(path: String, meta: RemoteFileMeta, content: String, folderId: String?, asConflict: Boolean) {
        val id = UUID.randomUUID().toString()
        val note = NoteEntity(
            id = id,
            title = titleOf(path),
            content = content,
            folderId = folderId,
            updatedAt = meta.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        if (asConflict) {
            noteDao.upsertNote(SyncMerger.conflictCopy(note, id, conflictSuffix, untitled))
            return
        }
        db.withTransaction {
            noteDao.upsertNote(note)
            fileDao.upsert(PlainFileEntity(id, path, meta.etag))
            historyDao.upsertBase(NoteSyncBaseEntity(id, note.title, content))
        }
    }

    /** Si el nombre del fichero es el que daría el título local, se conserva el título local (con sus `:` o `/`). */
    private fun keepTitle(local: NoteEntity?, path: String): String {
        val fromFile = titleOf(path)
        if (local != null && MarkdownFile.safeFileName(local.title.trim(), untitled) == fromFile) return local.title
        return fromFile
    }

    private suspend fun ensureFolders(segments: List<String>, cache: HashMap<String, String?>): String? {
        if (segments.isEmpty()) return null
        val key = segments.joinToString("/")
        if (cache.containsKey(key)) return cache[key]
        var parentId: String? = null
        for (name in segments) {
            val all = folderDao.getAll()
            val existing = all.firstOrNull {
                !it.isDeleted && it.parentId == parentId &&
                    (it.name.equals(name, ignoreCase = true) || MarkdownFile.safeFileName(it.name, "carpeta") == name)
            }
            parentId = existing?.id ?: FolderEntity(name = name, parentId = parentId, syncStatus = SyncStatus.SYNCED)
                .also { folderDao.upsertFolder(it) }.id
        }
        cache[key] = parentId
        return parentId
    }

    /** Markdown del servidor → interno: los enlaces a `.attachments/` pasan a `attachment://`. */
    private suspend fun fromRemote(client: WebDavClient, body: String, remoteAttachments: Map<String, RemoteFileMeta>): String {
        val text = body.removePrefix(0xFEFF.toChar().toString()).replace("\r\n", "\n").trimEnd()
        for (match in REMOTE_ATTACHMENT.findAll(text)) {
            val id = match.groupValues[1]
            if (attachments.exists(id)) continue
            val meta = remoteAttachments[id] ?: continue
            try {
                val bytes = client.download(meta.name)
                attachments.write(id, bytes)
                attachmentDao.upsert(
                    AttachmentEntity(
                        id = id,
                        mimeType = AttachmentStore.mimeForName(meta.name) ?: AttachmentStore.detectMimeType(bytes, "application/octet-stream"),
                        size = bytes.size.toLong(),
                        isUploaded = true
                    )
                )
            } catch (e: WebDavException) {
                Log.w(TAG, "No se pudo descargar ${meta.name}", e)
                warnings++
            }
        }
        return REMOTE_ATTACHMENT.replace(text) { m ->
            if (attachments.exists(m.groupValues[1])) AttachmentStore.reference(m.groupValues[1]) else m.value
        }
    }

    // ------------------------------------------------------------------
    // Subida
    // ------------------------------------------------------------------

    private fun desiredDir(note: NoteEntity, folders: Map<String, FolderEntity>): List<String> =
        FolderTree.path(folders, note.folderId).map { MarkdownFile.safeFileName(it.name, "carpeta") }

    private fun desiredPath(note: NoteEntity, folders: Map<String, FolderEntity>): String {
        val title = note.title.trim().ifBlank {
            note.content.lineSequence().map { it.trim().trimStart('#', '-', '>', ' ') }.firstOrNull { it.isNotBlank() }?.take(60) ?: untitled
        }
        return (desiredDir(note, folders) + (MarkdownFile.safeFileName(title, untitled) + ".md")).joinToString("/")
    }

    private suspend fun push(client: WebDavClient, remote: Map<String, RemoteFileMeta>, remoteAttachments: Map<String, RemoteFileMeta>) {
        val folders = folderDao.getAll().filter { !it.isDeleted }.associateBy { it.id }
        val mappings = fileDao.getAll().associateBy { it.noteId }
        // Rutas ocupadas (en minúsculas: muchos sistemas no distinguen mayúsculas).
        val taken = HashSet<String>().apply {
            remote.keys.forEach { add(it.lowercase()) }
            mappings.values.forEach { add(it.path.lowercase()) }
        }
        val uploadedAttachments = remoteAttachments.keys.toMutableSet()
        attachmentsDirReady = remoteAttachments.isNotEmpty()
        val createdDirs = HashSet<String>()

        for (note in noteDao.getAll()) {
            val mapping = mappings[note.id]
            try {
                when {
                    note.syncStatus == SyncStatus.PENDING_DELETE -> {
                        if (mapping != null) client.deleteIfMatch(mapping.path, mapping.etag)
                        db.withTransaction {
                            fileDao.delete(note.id)
                            noteDao.deletePermanently(note.id)
                        }
                    }
                    note.isDeleted -> {
                        // A la papelera: se quita del servidor (Nextcloud lo guarda en su propia papelera).
                        if (mapping != null) {
                            client.deleteIfMatch(mapping.path, mapping.etag)
                            fileDao.delete(note.id)
                        }
                        if (note.syncStatus == SyncStatus.PENDING_UPLOAD) noteDao.markSynced(note.id, note.updatedAt, null)
                    }
                    else -> pushNote(client, note, mapping, folders, taken, uploadedAttachments, createdDirs)
                }
            } catch (_: WebDavPreconditionFailedException) {
                // Cambió en el servidor entretanto: se fusiona en la siguiente ronda.
                rerun = true
            }
        }
    }

    private suspend fun pushNote(
        client: WebDavClient,
        note: NoteEntity,
        mapping: PlainFileEntity?,
        folders: Map<String, FolderEntity>,
        taken: HashSet<String>,
        uploadedAttachments: MutableSet<String>,
        createdDirs: HashSet<String>
    ) {
        var path = mapping?.path
        var etag = mapping?.etag
        val desired = desiredPath(note, folders)
        if (path == null || !path.equals(desired, ignoreCase = false)) {
            // Ruta nueva (nota nueva, título o carpeta cambiados): se busca un nombre libre.
            if (path != null) taken.remove(path.lowercase())
            val dir = desired.substringBeforeLast('/', "")
            val base = desired.substringAfterLast('/').removeSuffix(".md")
            val dirPrefix = if (dir.isEmpty()) "" else "${dir.lowercase()}/"
            // Nombres ya usados en esa carpeta (sin entrar en subcarpetas).
            val namesInDir = taken.filter { it.startsWith(dirPrefix) && '/' !in it.removePrefix(dirPrefix) }
                .mapTo(HashSet()) { it.removePrefix(dirPrefix) }
            val name = if (path != null && path.equals(desired, ignoreCase = true)) desired.substringAfterLast('/')
            else MarkdownFile.uniqueName(base, ".md", namesInDir)
            val target = if (dir.isEmpty()) name else "$dir/$name"
            if (dir.isNotEmpty() && createdDirs.add(dir)) client.mkdirs(dir)
            if (path != null && path != target) {
                client.move(path, target)
                etag = client.fetchEtag(target)
                fileDao.upsert(PlainFileEntity(note.id, target, etag))
            }
            path = target
            taken.add(target.lowercase())
        }
        if (note.syncStatus != SyncStatus.PENDING_UPLOAD) return

        val body = toRemote(client, note.content, path, uploadedAttachments)
        val newEtag = client.uploadText(path, body, ifMatch = etag, ifNoneMatch = etag == null) ?: client.fetchEtag(path)
        db.withTransaction {
            fileDao.upsert(PlainFileEntity(note.id, path, newEtag))
            historyDao.upsertBase(NoteSyncBaseEntity(note.id, note.title, note.content))
            noteDao.markSynced(note.id, note.updatedAt, null)
        }
    }

    /** Markdown interno → servidor: sube los adjuntos que falten y los enlaza con rutas relativas. */
    private suspend fun toRemote(client: WebDavClient, content: String, path: String, uploaded: MutableSet<String>): String {
        val depth = path.count { it == '/' }
        val prefix = "../".repeat(depth) + "$ATTACHMENTS_DIR/"
        val ids = LOCAL_ATTACHMENT.findAll(content).map { it.groupValues[1] }.toSet()
        val names = HashMap<String, String>()
        for (id in ids) {
            if (!attachments.exists(id)) continue
            val ext = AttachmentStore.fileNames(content)[id]?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.let { ".$it" }
                ?: AttachmentStore.extensionForMime(attachmentDao.getById(id)?.mimeType)
            names[id] = id + ext
            if (id in uploaded) continue
            if (!attachmentsDirReady) {
                client.mkdirs(ATTACHMENTS_DIR)
                attachmentsDirReady = true
            }
            try {
                client.upload("$ATTACHMENTS_DIR/$id$ext", attachments.read(id), AttachmentStore.mimeForName("x$ext") ?: "application/octet-stream", ifNoneMatch = true)
            } catch (_: WebDavPreconditionFailedException) {
                // Ya estaba.
            }
            uploaded += id
            attachmentDao.markUploaded(id)
        }
        return LOCAL_ATTACHMENT.replace(content) { m -> names[m.groupValues[1]]?.let { prefix + it } ?: m.value }.trimEnd() + "\n"
    }

    private suspend fun snapshot(note: NoteEntity, reason: NoteRevisionEntity.Reason) {
        if (note.title.isBlank() && note.content.isBlank()) return
        historyDao.insertRevision(NoteRevisionEntity(noteId = note.id, title = note.title, content = note.content, reason = reason.key))
    }
}
