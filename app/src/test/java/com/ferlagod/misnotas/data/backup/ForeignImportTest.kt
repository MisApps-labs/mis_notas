// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ForeignImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(text: String): File = tmp.newFile().apply { writeText(text) }

    @Test
    fun keepNotesChecklistsLabelsAndArchive() {
        val entries = mapOf(
            "Takeout/Keep/Compra.json" to file(
                """{"title":"Compra","isPinned":true,"isArchived":false,"isTrashed":false,"color":"CERULEAN",
                "listContent":[{"text":"pan","isChecked":true},{"text":"leche","isChecked":false}],
                "labels":[{"name":"Casa familiar"}],"userEditedTimestampUsec":1700000000000000}"""
            ),
            "Takeout/Keep/Viejo.json" to file("""{"title":"Viejo","textContent":"hola","isArchived":true}"""),
            "Takeout/Keep/Labels.txt" to file("x")
        )
        val notes = ForeignImport.parseKeep(entries, "Archivo").sortedBy { it.title }
        assertEquals(2, notes.size)
        val compra = notes[0]
        assertEquals("- [x] pan\n- [ ] leche", compra.content)
        assertTrue(compra.pinned)
        assertEquals("BLUE", compra.color)
        assertEquals(1_700_000_000_000L, compra.updatedAt)
        assertEquals("#Casa-familiar", ForeignImport.withTags(compra.content, compra.tags).lines().last())
        assertEquals(listOf("Google Keep", "Archivo"), notes[1].folderPath)
    }

    @Test
    fun simplenoteFirstLineIsTitle() {
        val json = JSONObject("""{"activeNotes":[{"content":"Título\r\n\nCuerpo","tags":["a"],"pinned":true,"lastModified":"2024-05-01T10:00:00.000Z"}],"trashedNotes":[{"content":"Borrada\nx"}]}""")
        val notes = ForeignImport.parseSimplenote(json)
        assertEquals("Título", notes[0].title.trim())
        assertEquals("Cuerpo", notes[0].content.trim())
        assertTrue(notes[1].trashed)
    }

    @Test
    fun standardNotesWithTagsAndEncryptedDetection() {
        val json = JSONObject(
            """{"items":[{"uuid":"n1","content_type":"Note","content":{"title":"T","text":"Texto"},"updated_at":"2024-01-02T03:04:05.000Z"},
            {"uuid":"t1","content_type":"Tag","content":{"title":"trabajo","references":[{"uuid":"n1"}]}}]}"""
        )
        assertTrue(ForeignImport.isStandardNotes(json))
        val notes = ForeignImport.parseStandardNotes(json)
        assertEquals(listOf("trabajo"), notes.single().tags)
        assertTrue(ForeignImport.isEncryptedStandardNotes(JSONObject("""{"items":[{"content":"004:abc"}]}""")))
    }

    @Test
    fun joplinFoldersTagsAndResources() {
        val folder = "Cuaderno\n\nid: f1\nparent_id: \ntype_: 2"
        val note = "Mi nota\n\nVer ![foto](:/0123456789abcdef0123456789abcdef)\n\nid: n1\nparent_id: f1\nupdated_time: 2024-01-01T00:00:00.000Z\nis_todo: 0\ntype_: 1"
        val res = "foto.png\n\nid: 0123456789abcdef0123456789abcdef\nmime: image/png\nfile_extension: png\ntype_: 4"
        val tag = "viaje\n\nid: t1\ntype_: 5"
        val nt = "id: x\nnote_id: n1\ntag_id: t1\ntype_: 6"
        val entries = mapOf(
            "f1.md" to file(folder), "n1.md" to file(note), "r.md" to file(res), "t1.md" to file(tag), "x.md" to file(nt),
            "resources/0123456789abcdef0123456789abcdef.png" to file("PNG")
        )
        val parsed = ForeignImport.parseJoplin(entries).single()
        assertEquals("Mi nota", parsed.title)
        assertEquals(listOf("Cuaderno"), parsed.folderPath)
        assertEquals(listOf("viaje"), parsed.tags)
        assertEquals("Ver ![foto](${ForeignImport.PLACEHOLDER}0123456789abcdef0123456789abcdef)", parsed.content)
        assertEquals(1, parsed.attachments.size)
    }

    @Test
    fun tarExtraction() {
        val out = ByteArrayOutputStream()
        fun entry(name: String, data: ByteArray) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "%011o".format(data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            out.write(h); out.write(data); out.write(ByteArray((512 - data.size % 512) % 512))
        }
        entry("a.md", "hola".toByteArray())
        entry("resources/b.png", ByteArray(700) { 1 })
        out.write(ByteArray(1024))
        val files = ForeignImport.extractTar(out.toByteArray().inputStream(), tmp.newFolder(), 10_000, 100_000)
        assertEquals("hola", files.getValue("a.md").readText())
        assertEquals(700L, files.getValue("resources/b.png").length())
    }

    @Test
    fun enexToMarkdown() {
        val enex = """<?xml version="1.0" encoding="UTF-8"?>
            <en-export><note><title>Receta</title>
            <content><![CDATA[<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE en-note SYSTEM "http://xml.evernote.com/pub/enml2.dtd">
            <en-note><h2>Ingredientes</h2><ul><li>Harina</li><li><b>Huevos</b></li></ul><div><en-todo checked="true"/>Horno</div><div><a href="https://ej.com">web</a></div><en-media hash="5d41402abc4b2a76b9719d911017c592" type="application/pdf"/></en-note>]]></content>
            <created>20240101T120000Z</created><tag>cocina</tag>
            <resource><data encoding="base64">aGVsbG8=</data><mime>application/pdf</mime><resource-attributes><file-name>receta.pdf</file-name></resource-attributes></resource>
            </note></en-export>"""
        val note = ForeignImport.parseEnex(enex.byteInputStream()).single()
        assertEquals("Receta", note.title)
        assertTrue(note.content, note.content.contains("## Ingredientes"))
        assertTrue(note.content, note.content.contains("- Harina"))
        assertTrue(note.content, note.content.contains("- **Huevos**"))
        assertTrue(note.content, note.content.contains("- [x] Horno"))
        assertTrue(note.content, note.content.contains("[web](https://ej.com)"))
        assertTrue(note.content, note.content.contains("[📎 receta.pdf](${ForeignImport.PLACEHOLDER}enex-5d41402abc4b2a76b9719d911017c592)"))
        assertEquals(listOf("cocina"), note.tags)
    }
}
