// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.integration

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.URLDecoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Servidor WebDAV mínimo para las pruebas: una sola carpeta, ETags, `If-Match`,
 * `If-None-Match: *`, PROPFIND (Depth 0 y 1), MKCOL, PUT, GET y DELETE.
 */
class FakeWebDav(private val root: String) : Dispatcher() {

    class Entry(val bytes: ByteArray, val etag: String)

    val files = ConcurrentHashMap<String, Entry>()
    private var rootExists = false
    private val counter = AtomicLong()

    fun put(name: String, bytes: ByteArray) {
        files[name] = Entry(bytes, "e" + counter.incrementAndGet())
    }

    fun text(name: String): String? = files[name]?.bytes?.toString(Charsets.UTF_8)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = URLDecoder.decode(request.requestUrl!!.encodedPath, "UTF-8")
        val isRoot = path.trimEnd('/') == root.trimEnd('/')
        val name = if (isRoot) "" else path.removePrefix(root.trimEnd('/') + "/")
        return when (request.method) {
            "MKCOL" -> if (rootExists) MockResponse().setResponseCode(405) else { rootExists = true; MockResponse().setResponseCode(201) }
            "PROPFIND" -> propfind(isRoot, name, request.getHeader("Depth") ?: "1")
            "HEAD" -> MockResponse().setResponseCode(200)
            "GET" -> files[name]?.let { MockResponse().setBody(Buffer().write(it.bytes)).setHeader("ETag", "\"${it.etag}\"") }
                ?: MockResponse().setResponseCode(404)
            "DELETE" -> { files.remove(name); MockResponse().setResponseCode(204) }
            "PUT" -> {
                val current = files[name]
                val ifMatch = request.getHeader("If-Match")?.trim('"')
                val ifNoneMatch = request.getHeader("If-None-Match")
                when {
                    ifMatch != null && current?.etag != ifMatch -> MockResponse().setResponseCode(412)
                    ifNoneMatch == "*" && current != null -> MockResponse().setResponseCode(412)
                    else -> {
                        put(name, request.body.readByteArray())
                        MockResponse().setResponseCode(if (current == null) 201 else 204).setHeader("ETag", "\"${files.getValue(name).etag}\"")
                    }
                }
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(isRoot: Boolean, name: String, depth: String): MockResponse {
        if (isRoot && !rootExists) return MockResponse().setResponseCode(404)
        val sb = StringBuilder("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
        if (isRoot) {
            sb.append("<d:response><d:href>$root/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>")
            if (depth != "0") files.forEach { (n, e) -> sb.append(entry(n, e)) }
        } else {
            val e = files[name] ?: return MockResponse().setResponseCode(404)
            sb.append(entry(name, e))
        }
        sb.append("</d:multistatus>")
        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(sb.toString())
    }

    private fun entry(name: String, e: Entry): String {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusDays(3))
        return "<d:response><d:href>${root.trimEnd('/')}/$name</d:href><d:propstat><d:prop>" +
            "<d:getetag>\"${e.etag}\"</d:getetag><d:getcontentlength>${e.bytes.size}</d:getcontentlength>" +
            "<d:getlastmodified>$date</d:getlastmodified><d:resourcetype/></d:prop></d:propstat></d:response>"
    }
}
