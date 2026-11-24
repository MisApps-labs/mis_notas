// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.backup

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Importación desde otras apps de notas. Cada formato se convierte en [ImportedNote]: Markdown
 * con los adjuntos referenciados como `attachment-import://<clave>` (se sustituyen al guardar)
 * y las etiquetas de origen, que se añaden como `#hashtags`.
 *
 * Formatos:
 * - Google Keep (Google Takeout: ficheros JSON en `Takeout/Keep`)
 * - Joplin (`.jex`, un TAR con los elementos en formato de texto de Joplin)
 * - Simplenote (exportación con `notes.json`)
 * - Standard Notes (copia de seguridad *sin cifrar*)
 * - Evernote (`.enex`)
 *
 * Son funciones puras sobre ficheros ya extraídos, para poder probarlas.
 */
object ForeignImport {

    const val PLACEHOLDER = "attachment-import://"

    data class ImportedAttachment(val key: String, val name: String, val mime: String?, val bytes: () -> ByteArray)

    data class ImportedNote(
        val title: String,
        val content: String,
        val updatedAt: Long? = null,
        val pinned: Boolean = false,
        val trashed: Boolean = false,
        val color: String? = null,
        val folderPath: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val attachments: List<ImportedAttachment> = emptyList()
    )

    /** Etiqueta como `#hashtag` válido (sin espacios). */
    fun tagToHashtag(tag: String): String? {
        val clean = tag.trim().replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}_/-]"), "")
        return if (clean.isEmpty() || !clean[0].isLetter()) null else "#$clean"
    }

    /** Contenido final: el texto y, al final, las etiquetas que no estén ya escritas. */
    fun withTags(content: String, tags: List<String>): String {
        val hashtags = tags.mapNotNull(::tagToHashtag).distinct().filter { !content.contains(it) }
        if (hashtags.isEmpty()) return content
        return content.trimEnd() + (if (content.isBlank()) "" else "\n\n") + hashtags.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // Google Keep
    // ------------------------------------------------------------------

    fun isKeepTakeout(paths: Collection<String>): Boolean =
        paths.any { it.contains("Keep/", ignoreCase = true) && it.endsWith(".json", ignoreCase = true) }

    fun parseKeep(entries: Map<String, File>, archivedFolder: String): List<ImportedNote> {
        val notes = mutableListOf<ImportedNote>()
        for ((path, file) in entries) {
            if (!path.contains("Keep/", ignoreCase = true) || !path.endsWith(".json", ignoreCase = true)) continue
            val json = try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                continue
            }
            if (!json.has("textContent") && !json.has("listContent") && !json.has("title")) continue
            val dir = path.substringBeforeLast('/', "")
            val body = StringBuilder(json.optString("textContent"))
            json.optJSONArray("listContent")?.let { list ->
                if (body.isNotEmpty()) body.append("\n\n")
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    body.append(if (item.optBoolean("isChecked")) "- [x] " else "- [ ] ").append(item.optString("text")).append('\n')
                }
            }
            val attachments = mutableListOf<ImportedAttachment>()
            json.optJSONArray("attachments")?.let { list ->
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    val name = a.optString("filePath").takeIf { it.isNotBlank() } ?: continue
                    val f = entries[if (dir.isEmpty()) name else "$dir/$name"] ?: continue
                    val key = "keep-${path.hashCode()}-$i"
                    attachments += ImportedAttachment(key, name, a.optString("mimetype").ifBlank { null }) { f.readBytes() }
                    body.append('\n').append(linkFor(name, a.optString("mimetype"), key))
                }
            }
            json.optJSONArray("annotations")?.let { list ->
                for (i in 0 until list.length()) {
                    val url = list.getJSONObject(i).optString("url").takeIf { it.isNotBlank() } ?: continue
                    body.append("\n").append(url)
                }
            }
            val labels = json.optJSONArray("labels").strings("name")
            val usec = json.optLong("userEditedTimestampUsec", 0L)
            notes += ImportedNote(
                title = json.optString("title"),
                content = body.toString().trimEnd(),
                updatedAt = if (usec > 0) usec / 1000 else null,
                pinned = json.optBoolean("isPinned"),
                trashed = json.optBoolean("isTrashed"),
                color = keepColor(json.optString("color")),
                folderPath = if (json.optBoolean("isArchived")) listOf("Google Keep", archivedFolder) else listOf("Google Keep"),
                tags = labels,
                attachments = attachments
            )
        }
        return notes
    }

    private fun keepColor(color: String): String? = when (color.uppercase()) {
        "RED", "PINK" -> "RED"
        "ORANGE", "BROWN" -> "ORANGE"
        "YELLOW" -> "YELLOW"
        "GREEN" -> "GREEN"
        "TEAL" -> "TEAL"
        "BLUE", "CERULEAN" -> "BLUE"
        "PURPLE" -> "PURPLE"
        else -> null
    }

    // ------------------------------------------------------------------
    // Simplenote
    // ------------------------------------------------------------------

    fun isSimplenote(json: JSONObject): Boolean = json.has("activeNotes")

    fun parseSimplenote(json: JSONObject): List<ImportedNote> {
        val out = mutableListOf<ImportedNote>()
        for ((key, trashed) in listOf("activeNotes" to false, "trashedNotes" to true)) {
            val list = json.optJSONArray(key) ?: continue
            for (i in 0 until list.length()) {
                val n = list.getJSONObject(i)
                val content = n.optString("content")
                // En Simplenote la primera línea es el título.
                val title = content.lineSequence().firstOrNull().orEmpty().trim().trimStart('#', ' ')
                val body = content.substringAfter('\n', "").trimStart('\n')
                out += ImportedNote(
                    title = title,
                    content = body,
                    updatedAt = parseIso(n.optString("lastModified")),
                    pinned = n.optBoolean("pinned"),
                    trashed = trashed,
                    folderPath = listOf("Simplenote"),
                    tags = n.optJSONArray("tags").strings()
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Standard Notes
    // ------------------------------------------------------------------

    /** Copia de Standard Notes. Una copia cifrada no se puede leer sin su contraseña. */
    fun isStandardNotes(json: JSONObject): Boolean = json.has("items") && json.optJSONArray("items") != null

    fun isEncryptedStandardNotes(json: JSONObject): Boolean =
        json.optJSONArray("items")?.let { items ->
            (0 until items.length()).any { items.getJSONObject(it).opt("content") is String }
        } == true

    fun parseStandardNotes(json: JSONObject): List<ImportedNote> {
        val items = json.getJSONArray("items")
        val tagsByNote = HashMap<String, MutableList<String>>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("content_type") != "Tag") continue
            val content = item.optJSONObject("content") ?: continue
            val name = content.optString("title")
            content.optJSONArray("references")?.let { refs ->
                for (r in 0 until refs.length()) {
                    tagsByNote.getOrPut(refs.getJSONObject(r).optString("uuid")) { mutableListOf() } += name
                }
            }
        }
        val out = mutableListOf<ImportedNote>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("content_type") != "Note") continue
            val content = item.optJSONObject("content") ?: continue
            val appData = content.optJSONObject("appData")?.optJSONObject("org.standardnotes.sn")
            out += ImportedNote(
                title = content.optString("title"),
                content = content.optString("text"),
                updatedAt = parseIso(item.optString("updated_at")),
                pinned = appData?.optBoolean("pinned") == true || content.optBoolean("pinned"),
                trashed = content.optBoolean("trashed"),
                folderPath = listOf("Standard Notes"),
                tags = tagsByNote[item.optString("uuid")].orEmpty()
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // Joplin (.jex)
    // ------------------------------------------------------------------

    private class JoplinItem(val title: String, val body: String, val meta: Map<String, String>) {
        val id get() = meta["id"].orEmpty()
        val type get() = meta["type_"]?.toIntOrNull() ?: 0
    }

    /** Elemento en formato de texto de Joplin: título, cuerpo y al final líneas `clave: valor`. */
    private fun parseJoplinItem(text: String): JoplinItem? {
        val lines = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
        var metaStart = lines.size
        val metaLine = Regex("^([a-z_]+): ?(.*)$")
        while (metaStart > 0 && metaLine.matches(lines[metaStart - 1])) metaStart--
        if (metaStart == lines.size) return null
        val meta = lines.subList(metaStart, lines.size).associate {
            val m = metaLine.find(it)!!
            m.groupValues[1] to m.groupValues[2]
        }
        val head = lines.subList(0, metaStart).joinToString("\n").trimEnd('\n')
        val title = head.substringBefore('\n')
        val body = if (head.contains('\n')) head.substringAfter('\n').removePrefix("\n") else ""
        return JoplinItem(title, body, meta)
    }

    fun parseJoplin(entries: Map<String, File>): List<ImportedNote> {
        val items = entries.filterKeys { it.endsWith(".md") && !it.contains('/') }
            .values.mapNotNull { runCatching { parseJoplinItem(it.readText()) }.getOrNull() }
        val folders = items.filter { it.type == 2 }.associateBy { it.id }
        val resources = items.filter { it.type == 4 }.associateBy { it.id }
        val tags = items.filter { it.type == 5 }.associateBy { it.id }
        val noteTags = HashMap<String, MutableList<String>>()
        items.filter { it.type == 6 }.forEach { nt ->
            val tag = tags[nt.meta["tag_id"]]?.title ?: return@forEach
            noteTags.getOrPut(nt.meta["note_id"].orEmpty()) { mutableListOf() } += tag
        }
        val resourceFiles = entries.filterKeys { it.startsWith("resources/") }
            .mapKeys { it.key.removePrefix("resources/").substringBeforeLast('.') }

        fun folderPath(id: String?): List<String> {
            val path = ArrayList<String>()
            var current = id
            var guard = 0
            while (!current.isNullOrEmpty() && guard++ < 50) {
                val f = folders[current] ?: break
                path.add(0, f.title)
                current = f.meta["parent_id"]
            }
            return path
        }

        val resourceRef = Regex("""(!?)\[([^\]]*)]\(:/([0-9a-f]{32})\)""")
        return items.filter { it.type == 1 }.map { note ->
            val attachments = mutableListOf<ImportedAttachment>()
            val body = resourceRef.replace(note.body) { m ->
                val rid = m.groupValues[3]
                val file = resourceFiles[rid]
                val res = resources[rid]
                if (file == null) return@replace m.value
                val name = res?.title?.ifBlank { null } ?: "$rid.${res?.meta?.get("file_extension").orEmpty()}"
                if (attachments.none { it.key == rid }) attachments += ImportedAttachment(rid, name, res?.meta?.get("mime")) { file.readBytes() }
                val image = m.groupValues[1] == "!" || res?.meta?.get("mime")?.startsWith("image/") == true
                if (image) "![${m.groupValues[2]}]($PLACEHOLDER$rid)" else "[📎 ${m.groupValues[2].ifBlank { name }}]($PLACEHOLDER$rid)"
            }
            val todo = note.meta["is_todo"] == "1"
            val title = if (todo && note.meta["todo_completed"].orEmpty().let { it.isNotEmpty() && it != "0" }) "✓ ${note.title}" else note.title
            ImportedNote(
                title = title,
                content = body,
                updatedAt = parseIso(note.meta["user_updated_time"] ?: note.meta["updated_time"]),
                folderPath = folderPath(note.meta["parent_id"]).ifEmpty { listOf("Joplin") },
                tags = noteTags[note.id].orEmpty(),
                attachments = attachments
            )
        }
    }

    /** Extrae un TAR (formato ustar) a ficheros temporales en [dir]. */
    fun extractTar(input: InputStream, dir: File, maxEntry: Long, maxTotal: Long): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val header = ByteArray(512)
        var total = 0L
        var index = 0
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            val size = cString(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val fullName = (if (prefix.isNotEmpty()) "$prefix/$name" else name).removePrefix("./")
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && size <= maxEntry && total + size <= maxTotal) {
                val path = BackupManager.normalizePath(fullName)
                val file = File(dir, (index++).toString())
                file.outputStream().use { o -> copyN(input, o, size) }
                skipN(input, padded - size)
                total += size
                if (path != null) out[path] = file
            } else {
                if (size > maxEntry || total + size > maxTotal) throw java.io.IOException("Archive too large")
                skipN(input, padded)
            }
        }
        return out
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, n: Long) {
        val buf = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw java.io.EOFException()
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun skipN(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(4096)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }

    // ------------------------------------------------------------------
    // Evernote (.enex)
    // ------------------------------------------------------------------

    fun parseEnex(input: InputStream): List<ImportedNote> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Sin entidades externas (XXE) ni DTD remotas.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }.parse(input)
        val out = mutableListOf<ImportedNote>()
        val notes = doc.getElementsByTagName("note")
        for (i in 0 until notes.length) {
            val note = notes.item(i) as Element
            val title = note.childText("title")
            val tags = note.childElements("tag").map { it.textContent.trim() }
            val updated = parseEnexDate(note.childText("updated")) ?: parseEnexDate(note.childText("created"))
            // Adjuntos: se identifican en el contenido por el MD5 de sus datos.
            val attachments = mutableListOf<ImportedAttachment>()
            val byHash = HashMap<String, ImportedAttachment>()
            val mimeByHash = HashMap<String, String?>()
            for (res in note.childElements("resource")) {
                val dataEl = res.childElements("data").firstOrNull() ?: continue
                val bytes = try {
                    Base64.getMimeDecoder().decode(dataEl.textContent.trim())
                } catch (_: Exception) {
                    continue
                }
                val hash = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                val mime = res.childText("mime").ifBlank { null }
                val name = res.childElements("resource-attributes").firstOrNull()?.childText("file-name")?.ifBlank { null }
                    ?: "adjunto-$hash"
                val a = ImportedAttachment("enex-$hash", name, mime) { bytes }
                byHash[hash] = a
                mimeByHash[hash] = mime
                attachments += a
            }
            val enml = note.childText("content")
            val markdown = try {
                enmlToMarkdown(enml, byHash, mimeByHash)
            } catch (_: Exception) {
                enml.replace(Regex("<[^>]+>"), " ")
            }
            out += ImportedNote(
                title = title,
                content = markdown,
                updatedAt = updated,
                folderPath = listOf("Evernote"),
                tags = tags,
                attachments = attachments
            )
        }
        return out
    }

    /** ENML (XHTML de Evernote) a Markdown: párrafos, títulos, listas, tareas, enlaces, formato y adjuntos. */
    fun enmlToMarkdown(
        enml: String,
        attachmentsByHash: Map<String, ImportedAttachment> = emptyMap(),
        mimeByHash: Map<String, String?> = emptyMap()
    ): String {
        val cleaned = enml.replace(Regex("<\\?xml[^>]*\\?>"), "").replace(Regex("<!DOCTYPE[^>]*>"), "")
            .replace("&nbsp;", " ")
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val root = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(cleaned))).documentElement
        val sb = StringBuilder()
        renderNode(root, sb, ListState(), attachmentsByHash, mimeByHash)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").replace(' ', ' ').trim()
    }

    private class ListState(var ordered: Boolean = false, var depth: Int = -1, var counter: Int = 0)

    private fun renderNode(node: Node, sb: StringBuilder, list: ListState, att: Map<String, ImportedAttachment>, mimes: Map<String, String?>) {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            sb.append(node.nodeValue.replace(Regex("\\s+"), " "))
            return
        }
        if (node.nodeType != Node.ELEMENT_NODE) return
        val el = node as Element
        fun children() {
            val kids = el.childNodes
            for (k in 0 until kids.length) renderNode(kids.item(k), sb, list, att, mimes)
        }
        fun blockBreak() {
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append(if (sb.endsWith("\n")) "\n" else "\n\n")
        }
        when (el.tagName.lowercase()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blockBreak(); sb.append("#".repeat(el.tagName[1].digitToInt())).append(' '); children(); sb.append("\n\n")
            }
            "p", "div" -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n'); children(); if (!sb.endsWith("\n")) sb.append('\n')
            }
            "br" -> sb.append('\n')
            "b", "strong" -> { sb.append("**"); children(); sb.append("**") }
            "i", "em" -> { sb.append("*"); children(); sb.append("*") }
            "s", "strike", "del" -> { sb.append("~~"); children(); sb.append("~~") }
            "code" -> { sb.append('`'); children(); sb.append('`') }
            "hr" -> { blockBreak(); sb.append("---\n\n") }
            "blockquote" -> {
                val inner = StringBuilder()
                val kids = el.childNodes
                for (k in 0 until kids.length) renderNode(kids.item(k), inner, list, att, mimes)
                blockBreak(); inner.toString().trim().lines().forEach { sb.append("> ").append(it).append('\n') }; sb.append('\n')
            }
            "a" -> {
                val href = el.getAttribute("href")
                sb.append('['); children(); sb.append("](").append(href).append(')')
            }
            "ul", "ol" -> {
                val saved = ListState(list.ordered, list.depth, list.counter)
                list.ordered = el.tagName.equals("ol", true); list.depth++; list.counter = 0
                if (saved.depth < 0) blockBreak()
                children()
                list.ordered = saved.ordered; list.depth = saved.depth; list.counter = saved.counter
                if (list.depth < 0) sb.append('\n')
            }
            "li" -> {
                if (!sb.endsWith("\n") && sb.isNotEmpty()) sb.append('\n')
                list.counter++
                sb.append("  ".repeat(maxOf(0, list.depth))).append(if (list.ordered) "${list.counter}. " else "- ")
                children()
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            "en-todo" -> sb.append(if (el.getAttribute("checked") == "true") "- [x] " else "- [ ] ")
            "en-media" -> {
                val hash = el.getAttribute("hash").lowercase()
                val a = att[hash]
                if (a != null) {
                    val mime = mimes[hash] ?: el.getAttribute("type")
                    sb.append('\n').append(linkFor(a.name, mime, a.key)).append('\n')
                }
            }
            "en-crypt" -> sb.append("[…]")
            "table" -> { blockBreak(); children(); sb.append('\n') }
            "tr" -> { children(); sb.append(" |\n") }
            "td", "th" -> { sb.append("| "); children(); sb.append(' ') }
            "style", "script", "title", "head" -> Unit
            else -> children()
        }
    }

    // ------------------------------------------------------------------

    private fun linkFor(name: String, mime: String?, key: String): String =
        if (mime?.startsWith("image/") == true || name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")) {
            "![${name.replace(']', ')')}]($PLACEHOLDER$key)"
        } else {
            "[📎 ${name.replace('[', '(').replace(']', ')')}]($PLACEHOLDER$key)"
        }

    private fun JSONArray?.strings(field: String? = null): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            if (field == null) optString(i).ifBlank { null } else optJSONObject(i)?.optString(field)?.ifBlank { null }
        }
    }

    private fun Element.childElements(tag: String): List<Element> {
        val kids = childNodes
        return (0 until kids.length).map { kids.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun Element.childText(tag: String): String = childElements(tag).firstOrNull()?.textContent?.trim().orEmpty()

    /** ISO 8601 (`2024-05-01T10:20:30.000Z`) o milisegundos. */
    fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { return it }
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseEnexDate(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time
        }.getOrNull()
    }
}
