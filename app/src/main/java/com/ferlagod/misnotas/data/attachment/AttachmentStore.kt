// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.attachment

import android.content.Context
import java.io.File

/**
 * Almacén de adjuntos en el almacenamiento privado de la app (`files/attachments/<id>`).
 *
 * En el Markdown de las notas los adjuntos se referencian como `![texto](attachment://<id>)`
 * (imágenes) o `[📎 nombre.pdf](attachment://<id>)` (cualquier otro fichero).
 */
class AttachmentStore(context: Context) {

    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun file(id: String): File = File(dir, sanitize(id))

    fun exists(id: String): Boolean = file(id).let { it.exists() && it.length() > 0 }

    fun read(id: String): ByteArray = file(id).readBytes()

    /** Escritura atómica: primero a un temporal y luego renombrado. */
    fun write(id: String, bytes: ByteArray) {
        val target = file(id)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(id: String) {
        file(id).delete()
    }

    companion object {
        const val DIR_NAME = "attachments"
        const val SCHEME = "attachment://"

        private val REF_REGEX = Regex("""attachment://([A-Za-z0-9_-]+)""")

        fun reference(id: String): String = "$SCHEME$id"

        fun referencedIds(markdown: String): Set<String> =
            REF_REGEX.findAll(markdown).map { it.groupValues[1] }.toSet()

        fun firstReferencedId(markdown: String): String? =
            REF_REGEX.find(markdown)?.groupValues?.get(1)

        /** Tamaño máximo de un fichero adjunto (se cifra entero en memoria al subirlo). */
        const val MAX_FILE_BYTES = 25L * 1024 * 1024

        const val FILE_PREFIX = "📎 "

        private val FILE_LINK = Regex("""(?<!!)\[([^\]]*)]\(attachment://([A-Za-z0-9_-]+)\)""")

        /** Enlace Markdown a un fichero adjunto. Los corchetes del nombre se sustituyen. */
        fun fileLink(name: String, id: String): String =
            "[$FILE_PREFIX${name.replace('[', '(').replace(']', ')')}](${reference(id)})"

        /** Nombre de fichero de cada adjunto enlazado como fichero (no como imagen) en [markdown]. */
        fun fileNames(markdown: String): Map<String, String> =
            FILE_LINK.findAll(markdown).associate { it.groupValues[2] to it.groupValues[1].removePrefix(FILE_PREFIX).trim() }

        /** Tipo MIME según la extensión de [name], o null si no se reconoce. */
        fun mimeForName(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }

        fun extensionForMime(mime: String?): String = when (mime) {
            null -> ".bin"
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ".bin"
        }

        fun fileFor(context: Context, id: String): File =
            File(File(context.applicationContext.filesDir, DIR_NAME), sanitize(id))

        /**
         * Tipo de imagen según sus primeros bytes (PNG, JPEG, GIF o WebP); [fallback] si no se
         * reconoce. Sirve para los adjuntos que llegan del servidor, que no llevan su tipo.
         */
        fun detectMimeType(bytes: ByteArray, fallback: String = "image/webp"): String {
            fun startsWith(vararg sig: Int, offset: Int = 0) =
                bytes.size >= offset + sig.size && sig.indices.all { bytes[offset + it] == sig[it].toByte() }
            return when {
                startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
                startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
                startsWith(0x52, 0x49, 0x46, 0x46) && startsWith(0x57, 0x45, 0x42, 0x50, offset = 8) -> "image/webp"
                startsWith(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
                startsWith(0x52, 0x49, 0x46, 0x46) && startsWith(0x57, 0x41, 0x56, 0x45, offset = 8) -> "audio/wav"
                startsWith(0x49, 0x44, 0x33) || startsWith(0xFF, 0xFB) -> "audio/mpeg"
                startsWith(0x4F, 0x67, 0x67, 0x53) -> "audio/ogg"
                startsWith(0x66, 0x74, 0x79, 0x70, offset = 4) && startsWith(0x4D, 0x34, 0x41, offset = 8) -> "audio/mp4"
                startsWith(0x66, 0x74, 0x79, 0x70, offset = 4) -> "video/mp4"
                else -> fallback
            }
        }

        private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
