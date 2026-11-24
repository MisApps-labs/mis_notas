// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.sync

import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.entity.NoteSyncBaseEntity
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.util.TextMerge

/** Acción a aplicar en local tras comparar una entidad local con su versión remota. */
sealed interface MergeAction<out T> {
    /** No hay nada que hacer. */
    data object Skip : MergeAction<Nothing>

    /** Borrar la fila local. */
    data object DeleteLocal : MergeAction<Nothing>

    /**
     * Mover la nota local a la papelera (recuperable) y recordar el ETag remoto. Se usa con
     * borrados que no se pueden verificar, para que nunca se pierda texto sin que el usuario lo vea.
     */
    data class TrashLocal(val etag: String?) : MergeAction<Nothing>

    /** Guardar la versión remota como sincronizada. */
    data class SaveRemote<T>(val entity: T) : MergeAction<T>

    /** Conservar los cambios locales pendientes y recordar el ETag remoto actual. */
    data class KeepLocal(val etag: String?) : MergeAction<Nothing>

    /**
     * Conservar la versión local y volver a subirla sobre el fichero remoto [etag]. Se usa cuando
     * lo que hay en el servidor no merece sustituir a lo local (p. ej. una lápida sin prueba).
     */
    data class Reupload(val etag: String?) : MergeAction<Nothing>

    /**
     * La nota cambió a la vez en local y en remoto, pero los cambios no se pisan: se guarda
     * [entity], que combina ambos (fusión a tres bandas con la última versión común), y se sube.
     */
    data class Merged(val entity: NoteEntity) : MergeAction<NoteEntity>

    /** Conflicto en una nota: se guarda [main] y se crea [copy] como nota nueva. */
    data class Conflict(val main: NoteEntity, val copy: NoteEntity) : MergeAction<NoteEntity>
}

/** Acción cuando una entidad local ya no existe en el servidor. */
enum class MissingRemoteAction {
    NOTHING,
    DELETE_LOCAL,
    MARK_FOR_UPLOAD,
    FORGET_ETAG,
    /** Nota: pasa a la papelera local y se vuelve a subir (como nota en papelera). */
    TRASH_AND_REUPLOAD,
    /** Se conserva y se vuelve a crear en el servidor (olvidando el ETag que ya no existe). */
    REUPLOAD
}

/**
 * Reglas de fusión de la sincronización. Son funciones puras para poder probarlas.
 *
 * Principio: nunca perder texto. Si una nota cambió a la vez en local y en remoto, se intenta
 * combinar ambos cambios con la última versión común ([NoteSyncBaseEntity]); si chocan en las
 * mismas líneas, gana la más reciente y la otra se conserva como "(conflicto)".
 */
object SyncMerger {

    const val CONFLICT_SUFFIX = " (conflict)"
    const val UNTITLED = "Untitled"

    fun resolveNote(
        local: NoteEntity?,
        remote: RemoteRecord<NoteEntity>,
        etag: String?,
        newId: () -> String,
        conflictSuffix: String = CONFLICT_SUFFIX,
        untitled: String = UNTITLED,
        base: NoteSyncBaseEntity? = null
    ): MergeAction<NoteEntity> {
        fun copyOf(note: NoteEntity) = asConflictCopy(note, newId(), conflictSuffix, untitled)
        when (remote) {
            is RemoteRecord.Tombstone -> return when {
                local == null -> MergeAction.Skip
                // Cambios locales sin subir: se conservan y resucitan la nota en el servidor.
                local.syncStatus == SyncStatus.PENDING_UPLOAD -> MergeAction.KeepLocal(etag)
                local.syncStatus == SyncStatus.PENDING_DELETE -> MergeAction.DeleteLocal
                // Borrado definitivo hecho por otro dispositivo con la misma clave.
                remote.verified -> MergeAction.DeleteLocal
                // Lápida sin prueba (versión antigua o servidor): a la papelera, recuperable.
                else -> MergeAction.TrashLocal(etag)
            }

            is RemoteRecord.Live -> {
                val remoteNote = remote.entity.copy(syncStatus = SyncStatus.SYNCED, remoteEtag = etag)
                if (local == null) return MergeAction.SaveRemote(remoteNote)

                return when (local.syncStatus) {
                    // Sin cambios locales se acepta lo remoto, salvo que sea más antiguo que lo que
                    // ya tenemos: eso es una vuelta atrás (el servidor reutiliza un fichero viejo o
                    // se restauró una versión anterior) y no debe aplicarse en silencio.
                    SyncStatus.SYNCED -> when {
                        remoteNote.updatedAt >= local.updatedAt -> MergeAction.SaveRemote(remoteNote)
                        local.title == remoteNote.title && local.content == remoteNote.content -> MergeAction.Reupload(etag)
                        else -> MergeAction.Conflict(
                            main = local.copy(remoteEtag = etag, syncStatus = SyncStatus.PENDING_UPLOAD),
                            copy = copyOf(remoteNote)
                        )
                    }

                    SyncStatus.PENDING_DELETE ->
                        if (remoteNote.updatedAt > local.updatedAt) MergeAction.SaveRemote(remoteNote)
                        else MergeAction.KeepLocal(etag)

                    SyncStatus.PENDING_UPLOAD -> when {
                        sameNoteContent(local, remoteNote) -> MergeAction.KeepLocal(etag)
                        else -> mergeNotes(local, remoteNote, base)?.let {
                            MergeAction.Merged(it.copy(remoteEtag = etag, syncStatus = SyncStatus.PENDING_UPLOAD))
                        } ?: when {
                        remoteNote.updatedAt > local.updatedAt -> MergeAction.Conflict(
                            main = remoteNote,
                            copy = copyOf(local)
                        )
                        else -> MergeAction.Conflict(
                            main = local.copy(remoteEtag = etag),
                            copy = copyOf(remoteNote)
                        )
                        }
                    }
                }
            }
        }
    }

    /**
     * Combina [local] y [remote]. Si el texto coincide y solo difieren carpeta, color, fijado o
     * papelera, se toman los de la versión más reciente. Si el texto difiere hace falta la base:
     * el título se combina si solo cambió en un lado y el contenido línea a línea ([TextMerge]).
     * Devuelve null si los cambios chocan.
     */
    fun mergeNotes(local: NoteEntity, remote: NoteEntity, base: NoteSyncBaseEntity?): NoteEntity? {
        val newer = if (remote.updatedAt > local.updatedAt) remote else local
        val title: String
        val content: String
        if (local.title == remote.title && local.content == remote.content) {
            title = local.title
            content = local.content
        } else {
            if (base == null) return null
            title = TextMerge.mergeLine(base.title, local.title, remote.title) ?: return null
            content = TextMerge.merge3(base.content, local.content, remote.content) ?: return null
        }
        return local.copy(
            title = title,
            content = content,
            folderId = newer.folderId,
            color = newer.color,
            isPinned = newer.isPinned,
            isDeleted = newer.isDeleted,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt)
        )
    }

    /**
     * Fusión de una nota en formato v1. Su texto va cifrado pero sin AAD y sus metadatos van en
     * claro, así que el servidor podría reutilizar cifrados viejos o cambiar fechas y estados.
     * Por eso un fichero v1 nunca sustituye a una nota local: si el texto difiere se guarda como
     * copia "(conflicto)". En todos los casos la nota se vuelve a subir en formato v2.
     */
    fun resolveLegacyNote(
        local: NoteEntity?,
        remote: NoteEntity,
        etag: String?,
        newId: () -> String,
        conflictSuffix: String = CONFLICT_SUFFIX,
        untitled: String = UNTITLED
    ): MergeAction<NoteEntity> = when {
        local == null -> MergeAction.SaveRemote(remote.copy(syncStatus = SyncStatus.PENDING_UPLOAD, remoteEtag = etag))
        local.syncStatus == SyncStatus.PENDING_DELETE -> MergeAction.KeepLocal(etag)
        local.title == remote.title && local.content == remote.content -> MergeAction.Reupload(etag)
        else -> MergeAction.Conflict(
            main = local.copy(remoteEtag = etag, syncStatus = SyncStatus.PENDING_UPLOAD),
            copy = asConflictCopy(remote, newId(), conflictSuffix, untitled)
        )
    }

    /** Como [resolveLegacyNote] para carpetas: se conserva la local y se reescribe en v2. */
    fun resolveLegacyFolder(local: FolderEntity?, remote: FolderEntity, etag: String?): MergeAction<FolderEntity> = when {
        local == null -> MergeAction.SaveRemote(remote.copy(syncStatus = SyncStatus.PENDING_UPLOAD, remoteEtag = etag))
        local.syncStatus == SyncStatus.PENDING_DELETE -> MergeAction.KeepLocal(etag)
        else -> MergeAction.Reupload(etag)
    }

    fun resolveFolder(
        local: FolderEntity?,
        remote: RemoteRecord<FolderEntity>,
        etag: String?
    ): MergeAction<FolderEntity> {
        when (remote) {
            is RemoteRecord.Tombstone -> return when {
                local == null -> MergeAction.Skip
                local.syncStatus == SyncStatus.PENDING_UPLOAD -> MergeAction.KeepLocal(etag)
                local.syncStatus == SyncStatus.PENDING_DELETE -> MergeAction.DeleteLocal
                // Borrado hecho por otro dispositivo con la misma clave.
                remote.verified -> MergeAction.DeleteLocal
                // Lápida sin prueba (versión antigua o el propio servidor): las carpetas no tienen
                // papelera, así que se conserva la carpeta y se vuelve a subir.
                else -> MergeAction.Reupload(etag)
            }

            is RemoteRecord.Live -> {
                val remoteFolder = remote.entity.copy(syncStatus = SyncStatus.SYNCED, remoteEtag = etag)
                if (local == null) return MergeAction.SaveRemote(remoteFolder)
                if (local.syncStatus == SyncStatus.SYNCED) {
                    // Una versión más antigua que la local es una vuelta atrás: se reescribe la local.
                    return if (remoteFolder.updatedAt >= local.updatedAt) MergeAction.SaveRemote(remoteFolder)
                    else MergeAction.Reupload(etag)
                }
                // Carpetas: gana la última modificación (no merece la pena duplicarlas).
                return if (remoteFolder.updatedAt > local.updatedAt) MergeAction.SaveRemote(remoteFolder)
                else MergeAction.KeepLocal(etag)
            }
        }
    }

    fun resolveMissingRemote(syncStatus: SyncStatus, remoteEtag: String?): MissingRemoteAction = when (syncStatus) {
        // Ya estaba en el servidor y ha desaparecido: otro dispositivo (o el usuario) lo borró.
        SyncStatus.SYNCED -> if (remoteEtag != null) MissingRemoteAction.DELETE_LOCAL else MissingRemoteAction.MARK_FOR_UPLOAD
        // Hay cambios locales: se vuelve a crear en el servidor.
        SyncStatus.PENDING_UPLOAD -> if (remoteEtag != null) MissingRemoteAction.FORGET_ETAG else MissingRemoteAction.NOTHING
        SyncStatus.PENDING_DELETE -> MissingRemoteAction.DELETE_LOCAL
    }

    /**
     * Como [resolveMissingRemote], pero para notas: si una nota sincronizada desaparece del
     * servidor sin lápida (alguien borró el fichero a mano, o el servidor perdió datos) no se
     * borra, sino que va a la papelera y se vuelve a subir.
     */
    fun resolveMissingRemoteNote(syncStatus: SyncStatus, remoteEtag: String?): MissingRemoteAction =
        when (val action = resolveMissingRemote(syncStatus, remoteEtag)) {
            MissingRemoteAction.DELETE_LOCAL ->
                if (syncStatus == SyncStatus.PENDING_DELETE) action else MissingRemoteAction.TRASH_AND_REUPLOAD
            else -> action
        }

    /**
     * Como [resolveMissingRemote], pero para carpetas: la app nunca borra el fichero de una
     * carpeta (deja una lápida), así que si una carpeta sincronizada desaparece del servidor es
     * que alguien borró el fichero a mano o el servidor perdió datos. No se borra: se vuelve a subir.
     */
    fun resolveMissingRemoteFolder(syncStatus: SyncStatus, remoteEtag: String?): MissingRemoteAction =
        if (syncStatus == SyncStatus.SYNCED && remoteEtag != null) MissingRemoteAction.REUPLOAD
        else resolveMissingRemote(syncStatus, remoteEtag)

    /** Por debajo de este número de notas retiradas en una ronda nunca se frena. */
    const val MASS_REMOVAL_MIN = 5

    /** A partir de este número de notas retiradas en una ronda siempre se frena. */
    const val MASS_REMOVAL_ABSOLUTE = 50

    /**
     * Freno de seguridad: ¿retirar [removed] notas visibles de las [live] que hay en el
     * dispositivo es tan anómalo que conviene no aplicarlo?
     *
     * "Retirar" es borrar o mandar a la papelera una nota que el usuario está viendo porque el
     * servidor dice que ya no existe (lápida o fichero desaparecido). Pasa con un solo fichero de
     * vez en cuando, pero en bloque suele indicar una carpeta remota equivocada, vaciada o
     * restaurada; en ese caso se conservan las notas y se vuelven a subir.
     *
     * No cuenta las notas que otro dispositivo mandó a la papelera de forma normal (llegan como
     * notas en papelera, no como lápidas) ni el vaciado de la papelera.
     */
    fun isMassRemoval(removed: Int, live: Int): Boolean =
        removed >= MASS_REMOVAL_MIN && (removed >= MASS_REMOVAL_ABSOLUTE || removed * 4 >= live)

    fun sameNoteContent(a: NoteEntity, b: NoteEntity): Boolean =
        a.title == b.title &&
            a.content == b.content &&
            a.folderId == b.folderId &&
            a.color == b.color &&
            a.isPinned == b.isPinned &&
            a.isDeleted == b.isDeleted

    private fun asConflictCopy(note: NoteEntity, id: String, suffix: String, untitled: String) =
        conflictCopy(note, id, suffix, untitled)

    /** Copia de [note] como nota nueva "(conflicto)", pendiente de subir. */
    fun conflictCopy(note: NoteEntity, id: String, suffix: String = CONFLICT_SUFFIX, untitled: String = UNTITLED) = note.copy(
        id = id,
        title = note.title.ifBlank { untitled } + suffix,
        isPinned = false,
        syncStatus = SyncStatus.PENDING_UPLOAD,
        remoteEtag = null
    )
}
