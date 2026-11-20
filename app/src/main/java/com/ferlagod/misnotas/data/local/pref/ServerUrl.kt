// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/ferlagod/mis_notas

package com.ferlagod.misnotas.data.local.pref

/** Limpieza de la dirección del servidor que escribe el usuario al conectar la cuenta. */
object ServerUrl {

    private const val DAV_FILES = "/remote.php/dav/files/"
    private const val LEGACY_WEBDAV = "/remote.php/webdav"
    private const val DAV = "/remote.php/dav"

    /**
     * Nextcloud muestra en Archivos → Ajustes una dirección WebDAV que apunta a la raíz de la
     * carpeta personal (`.../remote.php/dav/files/<usuario>/`). Usada tal cual, la app mezclaría
     * sus ficheros con los del usuario y la limpieza de adjuntos podría tocar ficheros suyos.
     *
     * - Raíz personal (`/dav/files/<usuario>`) → se añade la carpeta [AuthPreferences.DEFAULT_FOLDER_NAME].
     * - Una subcarpeta (`/dav/files/<usuario>/Notas`) → se respeta, la eligió el usuario.
     * - `/remote.php/webdav` o `/remote.php/dav` sin más → se quita y queda la dirección del servidor.
     */
    fun normalize(raw: String): String {
        val url = raw.trim().trimEnd('/')
        val filesAt = url.indexOf(DAV_FILES, ignoreCase = true)
        if (filesAt >= 0) {
            val segments = url.substring(filesAt + DAV_FILES.length).split('/').filter { it.isNotBlank() }
            return if (segments.size <= 1) "$url/${AuthPreferences.DEFAULT_FOLDER_NAME}" else url
        }
        for (suffix in listOf(LEGACY_WEBDAV, DAV)) {
            val at = url.indexOf(suffix, ignoreCase = true)
            if (at >= 0 && url.substring(at + suffix.length).trim('/').isEmpty()) return url.substring(0, at)
        }
        return url
    }
}
