// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.remote.webdav

import android.util.Xml
import com.ferlagod.misnotas.data.local.pref.ServerUrl
import com.ferlagod.misnotas.data.remote.http.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Cliente WebDAV mínimo (RFC 4918) para Nextcloud, ownCloud y cualquier servidor WebDAV.
 *
 * Usa ETags para detectar cambios y `If-Match` / `If-None-Match` en las subidas para no
 * sobrescribir nunca un cambio remoto que no hayamos visto.
 *
 * Solo acepta HTTPS, salvo direcciones de la red local (ver [ServerUrl.isAllowed]).
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    appPassword: String,
    private val okHttpClient: OkHttpClient
) {

    val rootUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val authHeader: String = Credentials.basic(username, appPassword, Charsets.UTF_8)

    init {
        require(ServerUrl.isAllowed(rootUrl)) { "Unencrypted HTTP is only allowed on the local network" }
    }

    companion object {
        private const val USER_AGENT = HttpClientProvider.USER_AGENT
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        private val PROPFIND_XML_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getlastmodified/>
                    <d:getetag/>
                    <d:resourcetype/>
                    <d:getcontentlength/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        fun normalizeEtag(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.trim().removePrefix("W/").trim('"').takeIf { it.isNotBlank() }
        }
    }

    private fun baseRequest(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", authHeader)
        .header("User-Agent", USER_AGENT)

    private fun Response.throwIfAuthError() {
        if (code == 401 || code == 403) throw WebDavAuthException(code)
    }

    /**
     * Comprueba credenciales y asegura que el directorio raíz de la app exista (lo crea con MKCOL).
     */
    suspend fun ensureRootFolder(): Unit = withContext(Dispatchers.IO) {
        val check = baseRequest(rootUrl)
            .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        okHttpClient.newCall(check).execute().use { response ->
            response.throwIfAuthError()
            if (response.isSuccessful) return@withContext
            if (response.code != 404) {
                throw WebDavException(response.code, "No se pudo acceder a la carpeta del servidor (${response.code})")
            }
        }

        val mkcol = baseRequest(rootUrl).method("MKCOL", null).build()
        okHttpClient.newCall(mkcol).execute().use { response ->
            response.throwIfAuthError()
            // 405 = ya existe
            if (response.isSuccessful || response.code == 405) return@withContext
            if (response.code == 409) {
                throw WebDavException(409, "WebDAV path not found", WebDavException.Reason.PATH_NOT_FOUND)
            }
            throw WebDavException(response.code, "No se pudo crear la carpeta en el servidor (${response.code})")
        }
    }

    /**
     * Sube un fichero.
     *
     * @param ifMatch si no es null, solo se sobrescribe si el ETag remoto coincide.
     * @param ifNoneMatch si es true, solo se crea si el fichero NO existe.
     * @return el nuevo ETag si el servidor lo devuelve.
     * @throws WebDavPreconditionFailedException si la precondición falla (412).
     */
    suspend fun upload(
        fileName: String,
        content: ByteArray,
        contentType: String,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val builder = baseRequest(fileUrl(fileName)).put(content.toRequestBody(contentType.toMediaType()))
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        else if (ifNoneMatch) builder.header("If-None-Match", "*")

        okHttpClient.newCall(builder.build()).execute().use { response ->
            response.throwIfAuthError()
            if (response.code == 412) throw WebDavPreconditionFailedException(fileName)
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "Error al subir $fileName (${response.code})")
            }
            normalizeEtag(response.header("OC-ETag") ?: response.header("ETag"))
        }
    }

    suspend fun uploadText(
        fileName: String,
        content: String,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false
    ): String? = upload(fileName, content.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", ifMatch, ifNoneMatch)

    /** Descarga un fichero; devuelve null si no existe (404). */
    suspend fun downloadOrNull(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = baseRequest(fileUrl(fileName)).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            response.throwIfAuthError()
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "Error al descargar $fileName (${response.code})")
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun download(fileName: String): ByteArray =
        downloadOrNull(fileName) ?: throw WebDavException(404, "No existe $fileName en el servidor")

    suspend fun downloadText(fileName: String): String = String(download(fileName), Charsets.UTF_8)

    /** Borrado idempotente (404 se considera éxito). */
    suspend fun delete(fileName: String): Unit = withContext(Dispatchers.IO) {
        val request = baseRequest(fileUrl(fileName)).delete().build()
        okHttpClient.newCall(request).execute().use { response ->
            response.throwIfAuthError()
            if (response.isSuccessful || response.code == 404) return@withContext
            throw WebDavException(response.code, "Error al eliminar $fileName (${response.code})")
        }
    }

    /** Obtiene el ETag actual de un fichero (o null si no existe). */
    suspend fun fetchEtag(fileName: String): String? = withContext(Dispatchers.IO) {
        val request = baseRequest(fileUrl(fileName))
            .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            response.throwIfAuthError()
            if (response.code == 404 || !response.isSuccessful) return@withContext null
            parsePropfindResponse(response.body?.string().orEmpty()).firstOrNull()?.etag
        }
    }

    /** Lista los ficheros (no directorios) del directorio raíz con PROPFIND Depth: 1. */
    suspend fun listRemoteFiles(): List<RemoteFileMeta> = withContext(Dispatchers.IO) {
        val request = baseRequest(rootUrl)
            .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        val xmlBody = okHttpClient.newCall(request).execute().use { response ->
            response.throwIfAuthError()
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "Error al listar ficheros del servidor (${response.code})")
            }
            response.body?.string().orEmpty()
        }

        if (xmlBody.isBlank()) emptyList() else parsePropfindResponse(xmlBody)
    }

    /** URL de un fichero de la carpeta; [fileName] puede llevar subcarpetas (`Trabajo/Nota.md`). */
    private fun fileUrl(fileName: String): String =
        rootUrl + fileName.removePrefix("/").split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Ruta (decodificada) de la carpeta raíz en el servidor, para calcular rutas relativas. */
    private val rootPath: String by lazy {
        URLDecoder.decode(rootUrl.toHttpUrl().encodedPath.replace("+", "%2B"), "UTF-8").trimEnd('/')
    }

    /**
     * Lista todos los ficheros bajo la carpeta raíz, entrando en subcarpetas (PROPFIND Depth: 1
     * en cada una, porque muchos servidores no admiten Depth: infinity). [RemoteFileMeta.name] es
     * la ruta relativa (`Trabajo/Nota.md`). Se saltan las carpetas que empiezan por punto salvo
     * [includeHidden].
     */
    suspend fun listRecursive(includeHidden: Set<String> = emptySet(), maxDepth: Int = 8): List<RemoteFileMeta> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RemoteFileMeta>()
        val queue = ArrayDeque(listOf("" to 0))
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val url = if (dir.isEmpty()) rootUrl else fileUrl(dir) + "/"
            val request = baseRequest(url)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .build()
            val xml = okHttpClient.newCall(request).execute().use { response ->
                response.throwIfAuthError()
                if (!response.isSuccessful) throw WebDavException(response.code, "Error al listar $dir (${response.code})")
                response.body?.string().orEmpty()
            }
            for (meta in parsePropfindResponse(xml, includeDirectories = true)) {
                // Algunos servidores devuelven el href como URL completa.
                val path = meta.path.replace(Regex("^https?://[^/]+"), "")
                val rel = path.removePrefix(rootPath).trim('/')
                if (rel.isEmpty() || rel == dir) continue
                if (meta.isDirectory) {
                    val last = rel.substringAfterLast('/')
                    if (depth + 1 < maxDepth && (!last.startsWith(".") || last in includeHidden)) queue += rel to depth + 1
                } else {
                    out += meta.copy(name = rel)
                }
            }
        }
        out
    }

    /** Crea [relativeDir] y las carpetas que falten por encima (MKCOL). */
    suspend fun mkdirs(relativeDir: String): Unit = withContext(Dispatchers.IO) {
        var current = ""
        for (part in relativeDir.split('/').filter { it.isNotBlank() }) {
            current = if (current.isEmpty()) part else "$current/$part"
            val request = baseRequest(fileUrl(current) + "/").method("MKCOL", null).build()
            okHttpClient.newCall(request).execute().use { response ->
                response.throwIfAuthError()
                if (!response.isSuccessful && response.code != 405) {
                    throw WebDavException(response.code, "No se pudo crear $current (${response.code})")
                }
            }
        }
    }

    /** Mueve o renombra un fichero sin sobrescribir el destino. Devuelve el ETag nuevo si lo hay. */
    suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val request = baseRequest(fileUrl(from))
            .method("MOVE", null)
            .header("Destination", fileUrl(to))
            .header("Overwrite", "F")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            response.throwIfAuthError()
            if (response.code == 412) throw WebDavPreconditionFailedException(to)
            if (!response.isSuccessful) throw WebDavException(response.code, "No se pudo mover $from (${response.code})")
        }
    }

    /** Borra solo si el ETag coincide (no se borra un cambio que no se ha visto). */
    suspend fun deleteIfMatch(fileName: String, etag: String?): Unit = withContext(Dispatchers.IO) {
        val builder = baseRequest(fileUrl(fileName)).delete()
        if (etag != null) builder.header("If-Match", "\"$etag\"")
        okHttpClient.newCall(builder.build()).execute().use { response ->
            response.throwIfAuthError()
            if (response.code == 412) throw WebDavPreconditionFailedException(fileName)
            if (response.isSuccessful || response.code == 404) return@use
            throw WebDavException(response.code, "Error al eliminar $fileName (${response.code})")
        }
    }

    private fun parsePropfindResponse(xml: String, includeDirectories: Boolean = false): List<RemoteFileMeta> {
        val result = mutableListOf<RemoteFileMeta>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var href: String? = null
        var lastModified: String? = null
        var etag: String? = null
        var contentLength = 0L
        var isCollection = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')?.lowercase().orEmpty()
            when (eventType) {
                XmlPullParser.START_TAG -> when (tag) {
                    "response" -> {
                        href = null; lastModified = null; etag = null
                        contentLength = 0L; isCollection = false
                    }
                    "href" -> href = parser.nextText()
                    "getlastmodified" -> lastModified = parser.nextText()
                    "getetag" -> etag = parser.nextText()
                    "getcontentlength" -> contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                    "collection" -> isCollection = true
                }
                XmlPullParser.END_TAG -> if (tag == "response" && href != null && (!isCollection || includeDirectories)) {
                    val path = URLDecoder.decode(href!!.replace("+", "%2B"), "UTF-8").trimEnd('/')
                    val name = path.substringAfterLast('/')
                    if (name.isNotBlank()) {
                        result += RemoteFileMeta(
                            name = name,
                            path = path,
                            isDirectory = isCollection,
                            lastModified = parseHttpDate(lastModified),
                            contentLength = contentLength,
                            etag = normalizeEtag(etag)
                        )
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
