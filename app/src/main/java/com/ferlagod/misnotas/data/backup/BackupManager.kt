// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/ferlagod/mis_notas

package com.ferlagod.misnotas.data.backup

import android.content.Context
import android.provider.DocumentsContract
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.ui.markdown.ImageSizing
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.AttachmentEntity
import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.model.FolderTree
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.ui.theme.NoteColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Resultado de una importación. */
data class ImportResult(val notes: Int, val skipped: Int)

/**
 * Copia de seguridad local en Markdown, independiente de Nextcloud.
 *
 * Exporta un ZIP con una carpeta por cada carpeta de la app, un `.md` por nota (con cabecera
 * de metadatos, ver [MarkdownFile]) y las imágenes en `attachments/`. El mismo ZIP, o ficheros
 * `.md` / `.txt` sueltos, se pueden volver a importar.
 */
class BackupManager(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val noteDao = database.noteDao()
    private val folderDao = database.folderDao()
    private val attachmentDao = database.attachmentDao()
    private val attachmentStore = AttachmentStore(appContext)

    // ------------------------------------------------------------------
    // Exportar
    // ------------------------------------------------------------------

    /** Escribe la copia en [uri] (creado con ACTION_CREATE_DOCUMENT). Devuelve el número de notas. */
    suspend fun exportZip(uri: Uri, untitled: String): Int = withContext(Dispatchers.IO) {
        val output = appContext.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open $uri")
        writeZip(output, untitled)
    }

    /**
     * Copia de seguridad automática antes de conectar una cuenta (la primera sincronización
     * fusiona las notas del dispositivo con las del servidor).
     *
     * Se guarda en el almacenamiento privado de la app, no en Descargas: es un ZIP sin cifrar y
     * en una carpeta pública lo podrían leer otras apps y seguiría ahí tras desinstalar. Se
     * conservan las [SAFETY_KEEP] más recientes; el usuario puede sacar la última desde Ajustes
     * con [saveLatestSafetyCopy]. Sin notas no se crea nada.
     *
     * @return el fichero creado, o null si no había nada que copiar.
     */
    suspend fun exportSafetyCopy(untitled: String): File? = withContext(Dispatchers.IO) {
        if (noteDao.countLive() == 0) return@withContext null
        val dir = safetyDir().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "$SAFETY_PREFIX$stamp.zip")
        val tmp = File(dir, "${file.name}.tmp")
        try {
            writeZip(FileOutputStream(tmp), untitled)
            if (!tmp.renameTo(file)) throw IOException("Cannot save ${file.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        safetyCopies().drop(SAFETY_KEEP).forEach { it.delete() }
        file
    }

    /** La copia previa a la conexión más reciente, o null si no hay ninguna. */
    fun latestSafetyCopy(): File? = safetyCopies().firstOrNull()

    /** Guarda la última copia previa a la conexión en [uri] (creado con ACTION_CREATE_DOCUMENT). */
    suspend fun saveLatestSafetyCopy(uri: Uri) = withContext(Dispatchers.IO) {
        val source = latestSafetyCopy() ?: throw IOException("No safety copy")
        val output = appContext.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    private fun safetyDir() = File(appContext.filesDir, SAFETY_DIR)

    /** Copias previas, de la más reciente a la más antigua (el nombre lleva la fecha). */
    private fun safetyCopies(): List<File> =
        safetyDir().listFiles { f -> f.isFile && f.name.startsWith(SAFETY_PREFIX) && f.name.endsWith(".zip") }
            .orEmpty()
            .sortedByDescending { it.name }

    /**
     * Copia automática en la carpeta [treeUri] elegida por el usuario (ACTION_OPEN_DOCUMENT_TREE).
     * Tras escribir la nueva copia se borran las más antiguas para conservar solo
     * [AUTO_BACKUP_KEEP]. Sin notas no se crea nada (así una base vacía no desplaza a copias buenas).
     *
     * @return el número de notas copiadas, o null si no había nada que copiar.
     */
    suspend fun exportAutoBackup(treeUri: Uri, untitled: String): Int? = withContext(Dispatchers.IO) {
        if (noteDao.countLive() == 0) return@withContext null
        val resolver = appContext.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", "$AUTO_BACKUP_PREFIX$stamp.zip")
            ?: throw IOException("Cannot create backup in $treeUri")
        val count = try {
            writeZip(resolver.openOutputStream(doc) ?: throw IOException("Cannot open $doc"), untitled)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        pruneAutoBackups(treeUri, treeDocId)
        count
    }

    /** Deja solo las [AUTO_BACKUP_KEEP] copias automáticas más recientes de la carpeta. */
    private fun pruneAutoBackups(treeUri: Uri, treeDocId: String) {
        val resolver = appContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val backups = ArrayList<Pair<String, String>>() // nombre → id de documento
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(AUTO_BACKUP_PREFIX)) backups += name to id
            }
        }
        // La marca de tiempo del nombre (yyyyMMdd-HHmmss) ordena cronológicamente.
        backups.sortedByDescending { it.first }.drop(AUTO_BACKUP_KEEP).forEach { (_, id) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, id)) }
        }
    }

    /** Escribe todas las notas visibles como ZIP en [output] (lo cierra). Devuelve el número de notas. */
    private suspend fun writeZip(output: OutputStream, untitled: String): Int {
        val notes = noteDao.getAll().filter { !it.isDeleted && it.syncStatus != SyncStatus.PENDING_DELETE }
        val folders = folderDao.getAll().filter { !it.isDeleted }
        val foldersById = folders.associateBy { it.id }

        ZipOutputStream(output.buffered()).use { zip ->
            val takenByDir = HashMap<String, MutableSet<String>>()
            val exportedAttachments = HashSet<String>()

            for (note in notes.sortedByDescending { it.updatedAt }) {
                val dirParts = FolderTree.path(foldersById, note.folderId).map { MarkdownFile.safeFileName(it.name, "carpeta") }
                val dir = dirParts.joinToString("/")
                val baseName = MarkdownFile.safeFileName(note.title.ifBlank { firstLine(note.content) ?: untitled }, untitled)
                val fileName = MarkdownFile.uniqueName(baseName, ".md", takenByDir.getOrPut(dir) { HashSet() })
                val path = if (dir.isEmpty()) fileName else "$dir/$fileName"

                // Las referencias internas se convierten en rutas relativas que cualquier editor entiende.
                val up = "../".repeat(dirParts.size)
                var body = note.content
                for (id in AttachmentStore.referencedIds(note.content)) {
                    if (!attachmentStore.exists(id)) continue
                    val attachmentPath = "$ATTACHMENTS_DIR/${id}${extensionFor(id)}"
                    body = body.replace(AttachmentStore.reference(id), up + attachmentPath)
                    if (exportedAttachments.add(id)) {
                        zip.putNextEntry(ZipEntry(attachmentPath))
                        zip.write(attachmentStore.read(id))
                        zip.closeEntry()
                    }
                }

                zip.putNextEntry(ZipEntry(path).apply { time = note.updatedAt })
                zip.write(MarkdownFile.write(note.title, body, note.isPinned, note.color, note.updatedAt).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return notes.size
    }

    private suspend fun extensionFor(id: String): String =
        when (attachmentDao.getById(id)?.mimeType) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            else -> ".webp"
        }

    // ------------------------------------------------------------------
    // Importar
    // ------------------------------------------------------------------

    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
            try {
                when {
                    name.endsWith(".zip", ignoreCase = true) -> {
                        val result = importZip(uri)
                        imported += result.notes
                        skipped += result.skipped
                    }
                    name.endsWith(".md", true) || name.endsWith(".markdown", true) || name.endsWith(".txt", true) -> {
                        val text = readLimited(openStream(uri), MAX_NOTE_BYTES).toString(Charsets.UTF_8)
                        val parsed = MarkdownFile.parse(text, name.substringBeforeLast('.'))
                        insertNote(parsed, folderId = null)
                        imported++
                    }
                    else -> skipped++
                }
            } catch (e: IOException) {
                skipped++
            }
        }
        ImportResult(imported, skipped)
    }

    private suspend fun importZip(uri: Uri): ImportResult {
        // Cada entrada se vuelca a un fichero temporal (no a memoria: un ZIP grande con fotos
        // agotaría la memoria y cerraría la app) para poder resolver luego las imágenes.
        val tempDir = File(appContext.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val entries = LinkedHashMap<String, File>()
            var total = 0L
            var index = 0
            ZipInputStream(openStream(uri).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = normalizePath(entry.name) ?: continue
                    if (path.split('/').any { it.startsWith("__MACOSX") || it.startsWith(".") }) continue
                    val file = File(tempDir, (index++).toString())
                    total += copyLimited(zip, file, MAX_ENTRY_BYTES, MAX_ZIP_BYTES - total)
                    entries[path] = file
                }
            }
            return importEntries(entries)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun importEntries(entries: Map<String, File>): ImportResult {
        var imported = 0
        var skipped = 0
        val folderCache = HashMap<String, String?>()
        val importedAttachments = HashMap<String, String>()

        for ((path, file) in entries) {
            val lower = path.lowercase()
            if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt"))) continue
            if (file.length() > MAX_NOTE_BYTES) {
                skipped++
                continue
            }

            val segments = path.split('/')
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)
            val parsed = MarkdownFile.parse(file.readText(Charsets.UTF_8), fileName.substringBeforeLast('.'))

            // 1) Imágenes locales del ZIP referenciadas por la nota → adjuntos.
            // El tamaño de la imagen (`#w=50`, ver ImageSizing) no forma parte de la ruta.
            fun resolve(target: String): String? {
                val path = ImageSizing.stripSize(target)
                return if (path.contains("://") || path.startsWith("data:")) null
                else normalizePath((dirSegments + path.split('/')).joinToString("/"))?.takeIf { it in entries }
            }

            for (match in IMAGE_LINK.findAll(parsed.content)) {
                val resolved = resolve(match.groupValues[2]) ?: continue
                if (resolved !in importedAttachments) {
                    importedAttachments[resolved] = storeAttachment(entries.getValue(resolved).readBytes(), resolved)
                }
            }
            // 2) Se reescriben los enlaces a `attachment://<id>`.
            val content = IMAGE_LINK.replace(parsed.content) { match ->
                val id = resolve(match.groupValues[2])?.let { importedAttachments[it] }
                if (id == null) match.value
                else "![${match.groupValues[1]}](${ImageSizing.withSize(AttachmentStore.reference(id), ImageSizing.widthPercent(match.groupValues[2]))})"
            }

            val folderId = try {
                ensureFolderPath(dirSegments, folderCache)
            } catch (_: Exception) {
                skipped++
                continue
            }
            insertNote(parsed.copy(content = content), folderId)
            imported++
        }
        return ImportResult(imported, skipped)
    }

    /**
     * Copia [input] en [target] sin pasar de [limit] bytes ni de lo que queda del total
     * ([remaining]). Devuelve los bytes copiados. No cierra [input].
     */
    private fun copyLimited(input: InputStream, target: File, limit: Long, remaining: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        FileOutputStream(target).use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                copied += n
                if (copied > limit) throw IOException("File too large")
                if (copied > remaining) throw IOException("ZIP too large")
                out.write(buffer, 0, n)
            }
        }
        return copied
    }

    private suspend fun storeAttachment(bytes: ByteArray, path: String): String {
        val id = UUID.randomUUID().toString()
        attachmentStore.write(id, bytes)
        val byExtension = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            else -> "image/webp"
        }
        // Manda el contenido: un .png que en realidad es JPEG se exporta luego como .jpg.
        val mime = AttachmentStore.detectMimeType(bytes, fallback = byExtension)
        attachmentDao.upsert(AttachmentEntity(id = id, mimeType = mime, size = bytes.size.toLong()))
        return id
    }

    /** Busca (o crea) la cadena de carpetas [segments] y devuelve el id de la última. */
    private suspend fun ensureFolderPath(segments: List<String>, cache: HashMap<String, String?>): String? {
        if (segments.isEmpty()) return null
        val key = segments.joinToString("/")
        if (cache.containsKey(key)) return cache[key]

        var parentId: String? = null
        for (name in segments) {
            val existing = folderDao.getAll().firstOrNull {
                !it.isDeleted && it.parentId == parentId && it.name.equals(name, ignoreCase = true)
            }
            parentId = existing?.id ?: FolderEntity(name = name, parentId = parentId).also { folderDao.upsertFolder(it) }.id
        }
        cache[key] = parentId
        return parentId
    }

    private suspend fun insertNote(parsed: ParsedMarkdownNote, folderId: String?) {
        database.withTransaction {
            noteDao.upsertNote(
                NoteEntity(
                    folderId = folderId,
                    title = parsed.title.trim(),
                    content = parsed.content,
                    isPinned = parsed.isPinned,
                    color = NoteColor.fromKey(parsed.color).key,
                    updatedAt = parsed.updatedAt ?: System.currentTimeMillis(),
                    syncStatus = SyncStatus.PENDING_UPLOAD
                )
            )
        }
    }

    // ------------------------------------------------------------------

    private fun openStream(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")

    private fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun readLimited(input: InputStream, limit: Long, closeStream: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IOException("File too large")
            }
        } finally {
            if (closeStream) input.close()
        }
        return out.toByteArray()
    }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"
        private const val SAFETY_DIR = "safety-copies"
        const val SAFETY_PREFIX = "MisNotas-backup-"
        const val SAFETY_KEEP = 3
        const val AUTO_BACKUP_PREFIX = "MisNotas-auto-"
        const val AUTO_BACKUP_KEEP = 3
        private const val MAX_NOTE_BYTES = 10L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
        private const val MAX_ZIP_BYTES = 500L * 1024 * 1024

        private val IMAGE_LINK = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

        private fun firstLine(content: String): String? =
            content.lineSequence().map { it.trim().trimStart('#', '-', '>', ' ') }.firstOrNull { it.isNotBlank() }?.take(60)

        /** Normaliza una ruta de ZIP ("a/./b/../c" → "a/c"); null si intenta salir de la raíz. */
        fun normalizePath(path: String): String? {
            val out = ArrayList<String>()
            for (part in path.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
                    else -> out += part
                }
            }
            return out.joinToString("/").ifEmpty { null }
        }
    }
}
