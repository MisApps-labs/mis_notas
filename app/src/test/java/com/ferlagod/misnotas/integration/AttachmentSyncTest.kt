// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.AttachmentEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.data.local.pref.AuthPreferences
import com.ferlagod.misnotas.data.local.pref.NextcloudCredentials
import com.ferlagod.misnotas.data.local.pref.ServerKind
import com.ferlagod.misnotas.data.sync.KeyInfoV3
import com.ferlagod.misnotas.data.sync.RemoteCodec
import com.ferlagod.misnotas.data.sync.RemoteRecord
import com.ferlagod.misnotas.data.sync.SyncRepository
import com.ferlagod.misnotas.security.Keyring
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sincronización real (base de datos, SyncRepository, cifrado) contra un servidor WebDAV falso:
 * los archivos adjuntos (no solo imágenes) suben cifrados, se descargan en "otro dispositivo",
 * y sobreviven a la actualización a v3 y a la rotación de clave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentSyncTest {

    private val root = "/dav/MisNotas"
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeWebDav
    private lateinit var context: Context
    private lateinit var repo: SyncRepository
    private lateinit var db: AppDatabase
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AuthPreferences.installForTesting(context.getSharedPreferences("test_auth", Context.MODE_PRIVATE))
        dav = FakeWebDav(root)
        server = MockWebServer().apply { dispatcher = dav; start() }
        repo = SyncRepository.getInstance(context)
        db = AppDatabase.getInstance(context)
        store = AttachmentStore(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ring(): Keyring = repo.authPreferences.getKeyring()!!

    private fun sync() = runBlocking {
        val result = repo.synchronize()
        assertTrue("sync: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun fileAttachmentsSyncBothWaysAndSurviveV3AndRotation() = runBlocking {
        val url = server.url(root).toString()
        val connected = repo.connectAccount(NextcloudCredentials(url, "ana", "secreto", ServerKind.WEBDAV), "contraseña maestra")
        assertTrue("connect: ${connected.exceptionOrNull()}", connected.isSuccess)

        // 1) Este dispositivo adjunta un PDF a una nota.
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(200_000) { (it % 251).toByte() }
        store.write("doc1", pdf)
        db.attachmentDao().upsert(AttachmentEntity(id = "doc1", mimeType = "application/pdf", size = pdf.size.toLong()))
        db.noteDao().upsertNote(
            NoteEntity(id = "n1", title = "Contrato", content = "Ver ${AttachmentStore.fileLink("contrato.pdf", "doc1")}", syncStatus = SyncStatus.PENDING_UPLOAD)
        )
        sync()

        val remoteAtt = dav.files["att_doc1.bin"]?.bytes ?: error("el adjunto no se ha subido")
        assertArrayEquals("el adjunto se sube cifrado y se puede descifrar", pdf, RemoteCodec.decryptAttachment(remoteAtt, "doc1", ring()))
        assertTrue("no viaja en claro", !String(remoteAtt, Charsets.ISO_8859_1).contains("%PDF"))
        val remoteNote = RemoteCodec.decodeNote(dav.text("note_n1.json")!!, "n1", ring()) as RemoteRecord.Live
        assertTrue(remoteNote.entity.content.contains("attachment://doc1"))
        assertTrue(db.attachmentDao().getById("doc1")!!.isUploaded)

        // 2) "Otro dispositivo" sube una nota con un audio adjunto: aquí se descarga.
        val audio = "OggS".toByteArray() + ByteArray(5_000) { 7 }
        dav.put("att_aud1.bin", RemoteCodec.encryptAttachment(audio, "aud1", ring()))
        dav.put(
            "note_n2.json",
            RemoteCodec.encodeNote(NoteEntity(id = "n2", title = "Reunión", content = AttachmentStore.fileLink("grabación.ogg", "aud1")), ring()).toByteArray()
        )
        sync()
        assertArrayEquals("el adjunto de otro dispositivo se descarga", audio, store.read("aud1"))
        assertEquals("el tipo sale del nombre del enlace", "audio/ogg", db.attachmentDao().getById("aud1")!!.mimeType)

        // 3) Actualizar a v3: todo se reescribe y los adjuntos siguen legibles.
        repo.withVault { client, ring -> repo.vault.upgradeToV3(client, ring, "contraseña maestra", withRecovery = true) }
        repo.reencryptAll()
        sync()
        assertTrue(KeyInfoV3.isV3(dav.text("keyinfo.json")!!))
        assertEquals(3, JSONObject(dav.text("note_n1.json")!!).getInt("version"))
        assertNotNull("v3 crea el manifiesto", dav.files["manifest.json"])

        // 4) Rotar la clave: los adjuntos del servidor quedan cifrados solo con la clave nueva.
        val before = ring()
        repo.withVault { client, ring -> repo.vault.rotateKey(client, ring) }
        repo.reencryptAll()
        sync()
        val after = ring()
        assertTrue(after.current != before.current)
        val onlyNew = Keyring(after.current, mapOf(after.current to after.currentKey))
        for (id in listOf("doc1", "aud1")) {
            val bytes = dav.files["att_$id.bin"]!!.bytes
            RemoteCodec.decryptAttachment(bytes, id, onlyNew)
            try {
                RemoteCodec.decryptAttachment(bytes, id, Keyring(before.current, mapOf(before.current to before.currentKey)))
                fail("$id sigue cifrado con la clave anterior")
            } catch (_: java.security.GeneralSecurityException) {
                // Esperado.
            }
        }
        assertEquals(after.current, JSONObject(dav.text("note_n2.json")!!).getString("kid"))

        // 5) Un adjunto que ya no usa ninguna nota no se borra antes del periodo de gracia.
        sync()
        assertNotNull(dav.files["att_doc1.bin"])
    }
}
