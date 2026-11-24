// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.remote.webdav

import android.content.Context
import com.ferlagod.misnotas.data.local.pref.ServerUrl
import com.ferlagod.misnotas.data.remote.http.HttpClientProvider
import com.ferlagod.misnotas.data.sync.KeyInfo
import com.ferlagod.misnotas.data.sync.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * "Probar conexión": comprueba paso a paso lo que la sincronización necesita del servidor y
 * explica qué falla. Cubre los problemas típicos de un servidor propio: DNS, certificados,
 * credenciales, rutas WebDAV, proxies que quitan cabeceras y servidores que no respetan
 * `If-Match` (necesario para no perder cambios entre dispositivos).
 *
 * Solo escribe un fichero temporal de prueba en la carpeta y lo borra al terminar.
 */
class ConnectionDiagnostics(private val context: Context) {

    enum class Step { ADDRESS, REACH, AUTH, FOLDER, WRITE, CONDITIONAL, LIST, CONTENTS }

    enum class Status { OK, WARNING, FAILED, SKIPPED }

    /** Problemas reconocibles; la interfaz los traduce a un mensaje con la solución. */
    enum class Issue {
        INVALID_URL,
        HTTP_NOT_LOCAL,
        HTTP_LOCAL,
        DNS,
        UNREACHABLE,
        TIMEOUT,
        CERT_UNTRUSTED,
        CERT_HOSTNAME,
        TLS_OTHER,
        AUTH,
        NOT_WEBDAV,
        PARENT_MISSING,
        HTTP_ERROR,
        NO_ETAG,
        IF_MATCH_IGNORED,
        IF_NONE_MATCH_IGNORED,
        FOREIGN_FILES,
        EXISTING_VAULT,
        EMPTY_FOLDER
    }

    data class StepResult(val step: Step, val status: Status, val issue: Issue? = null, val detail: String? = null)

    data class Report(
        val steps: List<StepResult>,
        /** Cadena del servidor si el certificado no es de confianza (para ofrecer confiar en él). */
        val untrustedChain: List<X509Certificate>? = null
    ) {
        val ok: Boolean get() = steps.none { it.status == Status.FAILED }
        val warnings: Int get() = steps.count { it.status == Status.WARNING }
    }

    suspend fun run(rootUrl: String, username: String, password: String): Report = withContext(Dispatchers.IO) {
        val results = mutableListOf<StepResult>()
        fun skipRest(from: Step) {
            Step.entries.filter { it.ordinal >= from.ordinal && results.none { r -> r.step == it } }
                .forEach { results += StepResult(it, Status.SKIPPED) }
        }

        // 1. Dirección
        val host = ServerUrl.host(rootUrl)
        when {
            host == null -> {
                results += StepResult(Step.ADDRESS, Status.FAILED, Issue.INVALID_URL)
                skipRest(Step.REACH); return@withContext Report(results)
            }
            !ServerUrl.isAllowed(rootUrl) -> {
                results += StepResult(Step.ADDRESS, Status.FAILED, Issue.HTTP_NOT_LOCAL)
                skipRest(Step.REACH); return@withContext Report(results)
            }
            !ServerUrl.isHttps(rootUrl) -> results += StepResult(Step.ADDRESS, Status.WARNING, Issue.HTTP_LOCAL)
            else -> results += StepResult(Step.ADDRESS, Status.OK, detail = host)
        }

        // 2. Conexión (DNS, TCP, TLS) sin credenciales
        val http = HttpClientProvider.get(context)
        try {
            http.newCall(Request.Builder().url(rootUrl).head().header("User-Agent", HttpClientProvider.USER_AGENT).build())
                .execute().use { results += StepResult(Step.REACH, Status.OK, detail = it.protocol.toString()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val issue = networkIssue(e)
            val chain = if (issue == Issue.CERT_UNTRUSTED) {
                runCatching { HttpClientProvider.fetchUntrustedChain(context, rootUrl) }.getOrNull()
            } else null
            results += StepResult(Step.REACH, Status.FAILED, issue, e.localizedMessage)
            skipRest(Step.AUTH)
            return@withContext Report(results, chain)
        }

        val client = WebDavClient(rootUrl, username, password, http)

        // 3 y 4. Credenciales y carpeta
        try {
            client.ensureRootFolder()
            results += StepResult(Step.AUTH, Status.OK, detail = username)
            results += StepResult(Step.FOLDER, Status.OK, detail = rootUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebDavAuthException) {
            results += StepResult(Step.AUTH, Status.FAILED, Issue.AUTH, "HTTP ${e.code}")
            skipRest(Step.FOLDER); return@withContext Report(results)
        } catch (e: WebDavException) {
            results += StepResult(Step.AUTH, Status.OK, detail = username)
            val issue = when {
                e.reason == WebDavException.Reason.PATH_NOT_FOUND -> Issue.PARENT_MISSING
                e.code == 405 || e.code == 501 || e.code == 400 -> Issue.NOT_WEBDAV
                else -> Issue.HTTP_ERROR
            }
            results += StepResult(Step.FOLDER, Status.FAILED, issue, "HTTP ${e.code}")
            skipRest(Step.WRITE); return@withContext Report(results)
        } catch (e: Exception) {
            results += StepResult(Step.AUTH, Status.FAILED, networkIssue(e), e.localizedMessage)
            skipRest(Step.FOLDER); return@withContext Report(results)
        }

        // 5 a 7. Escritura, subidas condicionales y listado, con un fichero temporal.
        val testName = ".misnotas-test-${UUID.randomUUID()}.tmp"
        var created = false
        try {
            val etag = client.upload(testName, "test".toByteArray(), "text/plain", ifNoneMatch = true)
                ?: client.fetchEtag(testName)
            created = true
            results += if (etag == null) StepResult(Step.WRITE, Status.WARNING, Issue.NO_ETAG)
            else StepResult(Step.WRITE, Status.OK)

            // Una subida con un ETag que no es el actual debe rechazarse (412).
            val ifMatchRespected = try {
                client.upload(testName, "x".toByteArray(), "text/plain", ifMatch = "misnotas-wrong-etag")
                false
            } catch (_: WebDavPreconditionFailedException) {
                true
            }
            val ifNoneMatchRespected = try {
                client.upload(testName, "y".toByteArray(), "text/plain", ifNoneMatch = true)
                false
            } catch (_: WebDavPreconditionFailedException) {
                true
            }
            results += when {
                !ifMatchRespected -> StepResult(Step.CONDITIONAL, Status.WARNING, Issue.IF_MATCH_IGNORED)
                !ifNoneMatchRespected -> StepResult(Step.CONDITIONAL, Status.WARNING, Issue.IF_NONE_MATCH_IGNORED)
                else -> StepResult(Step.CONDITIONAL, Status.OK)
            }

            val listed = client.listRemoteFiles()
            val test = listed.firstOrNull { it.name == testName }
            results += when {
                test == null -> StepResult(Step.LIST, Status.FAILED, Issue.HTTP_ERROR, "PROPFIND")
                test.etag == null -> StepResult(Step.LIST, Status.WARNING, Issue.NO_ETAG)
                else -> StepResult(Step.LIST, Status.OK, detail = listed.size.toString())
            }

            val others = listed.filter { it.name != testName }
            val foreign = others.count { !isAppFile(it.name) }
            results += when {
                foreign > 0 -> StepResult(Step.CONTENTS, Status.WARNING, Issue.FOREIGN_FILES, foreign.toString())
                others.any { it.name == KeyInfo.FILE_NAME } -> StepResult(Step.CONTENTS, Status.OK, Issue.EXISTING_VAULT, others.size.toString())
                else -> StepResult(Step.CONTENTS, Status.OK, Issue.EMPTY_FOLDER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebDavException) {
            if (results.none { it.step == Step.WRITE }) {
                results += StepResult(Step.WRITE, Status.FAILED, if (e is WebDavAuthException) Issue.AUTH else Issue.HTTP_ERROR, "HTTP ${e.code}")
            } else {
                results += StepResult(Step.entries.first { s -> results.none { it.step == s } }, Status.FAILED, Issue.HTTP_ERROR, "HTTP ${e.code}")
            }
        } catch (e: Exception) {
            results += StepResult(Step.entries.first { s -> results.none { it.step == s } }, Status.FAILED, networkIssue(e), e.localizedMessage)
        } finally {
            if (created) runCatching { client.delete(testName) }
        }
        skipRest(Step.WRITE)
        Report(results.sortedBy { it.step.ordinal })
    }

    private fun isAppFile(name: String): Boolean =
        name == KeyInfo.FILE_NAME || name.startsWith(".misnotas") ||
            (name.startsWith(SyncRepository.NOTE_PREFIX) && name.endsWith(SyncRepository.JSON_EXT)) ||
            (name.startsWith(SyncRepository.FOLDER_PREFIX) && name.endsWith(SyncRepository.JSON_EXT)) ||
            (name.startsWith(SyncRepository.ATTACHMENT_PREFIX) && name.endsWith(SyncRepository.BIN_EXT))

    companion object {
        fun networkIssue(e: Throwable): Issue {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is UnknownHostException -> return Issue.DNS
                    is SSLPeerUnverifiedException -> return Issue.CERT_HOSTNAME
                    is SSLHandshakeException ->
                        return if (cause.cause is java.security.cert.CertificateException ||
                            cause.message?.contains("Trust anchor", ignoreCase = true) == true ||
                            cause.message?.contains("certification path", ignoreCase = true) == true
                        ) Issue.CERT_UNTRUSTED else Issue.TLS_OTHER
                    is javax.net.ssl.SSLException -> return Issue.TLS_OTHER
                    is SocketTimeoutException -> return Issue.TIMEOUT
                    is ConnectException, is NoRouteToHostException -> return Issue.UNREACHABLE
                }
                cause = cause.cause
            }
            return Issue.UNREACHABLE
        }
    }
}
