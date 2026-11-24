// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.pref

import java.net.URI

/** Tipo de servidor al que se conecta la cuenta. */
enum class ServerKind(val key: String) {
    /** Nextcloud u ownCloud: la app calcula la ruta WebDAV a partir de la dirección del servidor. */
    NEXTCLOUD("nextcloud"),

    /** Cualquier servidor WebDAV: la dirección es directamente la carpeta donde guardar las notas. */
    WEBDAV("webdav");

    companion object {
        fun fromKey(key: String?): ServerKind = entries.firstOrNull { it.key == key } ?: NEXTCLOUD
    }
}

/** Limpieza de la dirección del servidor que escribe el usuario al conectar la cuenta. */
object ServerUrl {

    private const val DAV_FILES = "/remote.php/dav/files/"
    private const val LEGACY_WEBDAV = "/remote.php/webdav"
    private const val DAV = "/remote.php/dav"

    /** Sufijos de nombres de equipo que solo existen dentro de una red local. */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    /** Añade `https://` si el usuario escribió solo el nombre del servidor. */
    fun withScheme(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return url
        return if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) url
        else "https://$url"
    }

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
        val url = withScheme(raw).trimEnd('/')
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

    /** Para un servidor WebDAV genérico la dirección es la carpeta: solo se limpia. */
    fun normalizeWebDav(raw: String): String = withScheme(raw).trimEnd('/')

    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun host(url: String): String? = try {
        URI(url.trim()).host?.lowercase()?.trim('[', ']')
    } catch (_: Exception) {
        null
    }

    /**
     * true si [host] solo es alcanzable dentro de una red local: `localhost`, direcciones privadas
     * (10/8, 172.16/12, 192.168/16, 100.64/10 de Tailscale/CGNAT, enlace local) o nombres como
     * `nas.local`. Es el único caso en el que la app acepta HTTP sin cifrar.
     */
    fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (!h.contains('.') && !h.contains(':')) return true // nombre corto de la red local ("nas")
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = parts[0].toInt() to parts[1].toInt()
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        if (h.contains(':')) {
            // IPv6: bucle local, ULA (fc00::/7) y enlace local (fe80::/10).
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
                h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        return false
    }

    /** ¿Se permite conectar a [url]? HTTPS siempre; HTTP solo dentro de la red local. */
    fun isAllowed(url: String): Boolean {
        val trimmed = url.trim()
        if (isHttps(trimmed)) return true
        return trimmed.startsWith("http://", ignoreCase = true) && isLocalHost(host(trimmed))
    }
}
