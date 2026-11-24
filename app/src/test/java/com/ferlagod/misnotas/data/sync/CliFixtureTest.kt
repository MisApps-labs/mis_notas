// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.sync

import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.security.Argon2
import com.ferlagod.misnotas.security.CryptoManager
import com.ferlagod.misnotas.security.Keyring
import com.ferlagod.misnotas.security.RecoveryKey
import org.junit.Test
import java.io.File

/**
 * Genera bóvedas de ejemplo (v2 y v3) en `app/build/cli-fixture/` con el código real de la app,
 * para comprobar que tools/misnotas-decrypt las descifra (ver su README).
 */
class CliFixtureTest {

    @Test
    fun writeFixtures() {
        val root = File("build/cli-fixture").apply { deleteRecursively(); mkdirs() }
        val password = "Contraseña de prueba"

        // v2
        val salt = CryptoManager.generateSalt()
        val key = CryptoManager.deriveKey(CryptoManager.canonicalPassword(password), salt, CryptoManager.MIN_PBKDF2_ITERATIONS)
        write(root, "v2", KeyInfo.create(salt, CryptoManager.MIN_PBKDF2_ITERATIONS, key).toJson(), Keyring.legacy(key))

        // v3 con una rotación, para que haya ficheros de dos claves
        val ring = Keyring(Keyring.LEGACY_KID, mapOf(Keyring.LEGACY_KID to key), KeyInfoV3.newVmk())
        val rotated = ring.rotated()
        val params = Argon2.Params(memoryKiB = 8_192, iterations = 1, parallelism = 1)
        val (info, recovery) = KeyInfoV3.create(password, ring, params).withKeyring(rotated).withNewRecovery(ring.vmk!!)
        val dir = write(root, "v3", info.toJson(), rotated)
        // Un fichero que quedó cifrado con la clave anterior.
        File(dir, "note_old.json").writeText(RemoteCodec.encodeNote(NoteEntity(id = "old", title = "Antigua", content = "de antes"), ring))
        File(root, "v3-recovery.txt").writeText(RecoveryKey.format(recovery))
        File(root, "password.txt").writeText(password)
    }

    private fun write(root: File, name: String, keyinfo: String, ring: Keyring): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "keyinfo.json").writeText(keyinfo)
        val folder = FolderEntity(id = "f1", name = "Trabajo")
        val child = FolderEntity(id = "f2", name = "Proyecto", parentId = "f1")
        File(dir, "folder_f1.json").writeText(RemoteCodec.encodeFolder(folder, ring))
        File(dir, "folder_f2.json").writeText(RemoteCodec.encodeFolder(child, ring))
        File(dir, "att_img1.bin").writeBytes(RemoteCodec.encryptAttachment(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3), "img1", ring))
        File(dir, "att_doc1.bin").writeBytes(RemoteCodec.encryptAttachment("%PDF-1.7 hola".toByteArray(), "doc1", ring))
        val note = NoteEntity(
            id = "n1", folderId = "f2", title = "Plan: Q1", isPinned = true, color = "BLUE",
            content = "Texto con ñ\n\n![foto](attachment://img1)\n\n[📎 informe.pdf](attachment://doc1)\n\n#etiqueta"
        )
        File(dir, "note_n1.json").writeText(RemoteCodec.encodeNote(note, ring, rev = 3))
        File(dir, "note_n2.json").writeText(RemoteCodec.encodeNote(NoteEntity(id = "n2", title = "En papelera", content = "x", isDeleted = true), ring))
        File(dir, "note_n3.json").writeText(RemoteCodec.encodeTombstone(RemoteCodec.TYPE_NOTE, "n3", 5L, ring))
        return dir
    }
}
