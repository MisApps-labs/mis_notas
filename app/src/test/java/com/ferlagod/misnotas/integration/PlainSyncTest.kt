// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.AttachmentEntity
import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.data.local.pref.AuthPreferences
import com.ferlagod.misnotas.data.local.pref.NextcloudCredentials
import com.ferlagod.misnotas.data.local.pref.ServerKind
import com.ferlagod.misnotas.data.sync.SyncRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modo sin cifrar (compatible con Nextcloud Notes) contra un servidor WebDAV falso. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainSyncTest {

    private val root = "/dav/Notes"
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeWebDav
    private lateinit var repo: SyncRepository
    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SyncRepository.resetForTesting()
        context.deleteDatabase("misnotas.db")
        AuthPreferences.installForTesting(context.getSharedPreferences("test_auth_plain", Context.MODE_PRIVATE))
        dav = FakeWebDav(root)
        server = MockWebServer().apply { dispatcher = dav; start() }
        repo = SyncRepository.getInstance(context)
        db = AppDatabase.getInstance(context)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun creds() = NextcloudCredentials(server.url(root).toString(), "ana", "x", ServerKind.WEBDAV, encrypted = false)

    private fun sync() = runBlocking {
        val r = repo.synchronize()
        assertTrue("sync: ${r.exceptionOrNull()}", r.isSuccess)
    }

    private fun note(id: String) = runBlocking { db.noteDao().getNoteById(id) }

    @Test
    fun refusesAFolderWithAnEncryptedVault() = runBlocking {
        dav.put("keyinfo.json", "{}".toByteArray())
        assertTrue(repo.connectAccount(creds(), "").isFailure)
    }

    @Test
    fun notesAreReadableMarkdownAndSyncBothWays() = runBlocking {
        assertTrue(repo.connectAccount(creds(), "").isSuccess)
        assertTrue(repo.authPreferences.isPlainMode())

        // 1) Nota local en una carpeta, con un título con ":" y una imagen.
        val folder = FolderEntity(id = "f1", name = "Trabajo")
        db.folderDao().upsertFolder(folder)
        val img = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)
        AttachmentStore(context).write("img1", img)
        db.attachmentDao().upsert(AttachmentEntity(id = "img1", mimeType = "image/png", size = img.size.toLong()))
        db.noteDao().upsertNote(
            NoteEntity(id = "n1", folderId = "f1", title = "Plan: Q1", content = "uno\ndos\ntres\n\n![x](attachment://img1)", syncStatus = SyncStatus.PENDING_UPLOAD)
        )
        sync()
        val body = dav.text("Trabajo/Plan_ Q1.md")
        assertNotNull("se sube como Markdown legible", body)
        assertTrue(body!!.startsWith("uno\ndos\ntres"))
        assertTrue(body.contains("](../.attachments/img1.png)"))
        assertArrayEquals(img, dav.files[".attachments/img1.png"]!!.bytes)

        // 2) Se edita en el servidor (p. ej. en Nextcloud Notes): llega y el título se conserva.
        dav.put("Trabajo/Plan_ Q1.md", "uno\ndos remoto\ntres\n\n![x](../.attachments/img1.png)\n".toByteArray())
        sync()
        assertEquals("Plan: Q1", note("n1")!!.title)
        assertTrue(note("n1")!!.content.startsWith("uno\ndos remoto"))
        assertTrue("los adjuntos vuelven a attachment://", note("n1")!!.content.contains("attachment://img1"))

        // 3) Nota creada en el servidor, en otra carpeta.
        dav.put("Ideas/Nueva idea.md", "texto".toByteArray())
        sync()
        val nueva = db.noteDao().getAll().single { it.title == "Nueva idea" }
        assertEquals("Ideas", db.folderDao().getFolderById(nueva.folderId!!)!!.name)

        // 4) Cambios a la vez en líneas distintas: se fusionan.
        db.noteDao().upsertNote(note("n1")!!.copy(content = note("n1")!!.content.replace("tres", "tres local"), syncStatus = SyncStatus.PENDING_UPLOAD, updatedAt = System.currentTimeMillis()))
        dav.put("Trabajo/Plan_ Q1.md", "uno remoto\ndos remoto\ntres\n\n![x](../.attachments/img1.png)\n".toByteArray())
        sync()
        val merged = note("n1")!!.content
        assertTrue(merged, merged.startsWith("uno remoto\ndos remoto\ntres local"))
        assertTrue(dav.text("Trabajo/Plan_ Q1.md")!!.contains("tres local"))
        assertEquals(1, db.noteDao().getAll().count { it.title.startsWith("Plan") })

        // 5) Renombrar en la app: se mueve el fichero.
        db.noteDao().upsertNote(note("n1")!!.copy(title = "Plan anual", syncStatus = SyncStatus.PENDING_UPLOAD, updatedAt = System.currentTimeMillis()))
        sync()
        assertNull(dav.files["Trabajo/Plan_ Q1.md"])
        assertNotNull(dav.files["Trabajo/Plan anual.md"])

        // 6) Renombrar en el servidor (mismo ETag): se reconoce, sin duplicar.
        val entry = dav.files.remove("Ideas/Nueva idea.md")!!
        dav.files["Ideas/Idea buena.md"] = entry
        sync()
        assertEquals("Idea buena", note(nueva.id)!!.title)
        assertFalse(db.noteDao().getAll().any { it.title == "Nueva idea" })

        // 7) Borrado en el servidor: a la papelera, no se pierde.
        dav.files.remove("Ideas/Idea buena.md")
        sync()
        assertTrue(note(nueva.id)!!.isDeleted)

        // 8) Mandar a la papelera en la app: se quita del servidor.
        db.noteDao().moveToTrash("n1")
        sync()
        assertNull(dav.files["Trabajo/Plan anual.md"])
    }
}
