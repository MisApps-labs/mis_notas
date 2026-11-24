// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.remote.http

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Cliente HTTP compartido por la sincronización, el inicio de sesión y la prueba de conexión.
 * Confía en los certificados del sistema y en los que el usuario haya añadido
 * (ver [TrustedCertificates]); se vuelve a crear cuando esa lista cambia.
 */
object HttpClientProvider {

    const val USER_AGENT = "MisNotas-Android"

    @Volatile
    private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) { client ?: build(context.applicationContext).also { client = it } }
    }

    fun invalidate() {
        synchronized(this) { client = null }
    }

    private fun build(context: Context): OkHttpClient {
        val trustManager = CompositeTrustManager(
            TrustedCertificates.systemTrustManager(),
            TrustedCertificates.trustManagerFor(TrustedCertificates(context).list())
        )
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    /**
     * Obtiene la cadena de certificados que presenta [url] sin confiar en ella ni enviar nada:
     * solo se hace el saludo TLS. Sirve para enseñar al usuario la huella del certificado de un
     * servidor autofirmado y que decida si confía en él.
     *
     * @return la cadena (el primero es el del servidor), o null si el sistema ya confía en ella.
     */
    suspend fun fetchUntrustedChain(context: Context, url: String): List<X509Certificate>? = withContext(Dispatchers.IO) {
        val uri = URI(url.trim())
        val host = uri.host ?: return@withContext null
        val port = if (uri.port > 0) uri.port else 443

        // Primero: ¿ya es de confianza (sistema o usuario)?
        try {
            get(context).newCall(Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build())
                .execute().close()
            return@withContext null
        } catch (_: SSLHandshakeException) {
            // Seguimos: hay que enseñar la cadena.
        } catch (e: Exception) {
            if (e.cause !is SSLHandshakeException && e !is javax.net.ssl.SSLPeerUnverifiedException) throw e
        }

        var captured: Array<X509Certificate>? = null
        // Rechaza siempre: solo lee la cadena para enseñar su huella, nunca envía datos por ella.
        val capturing = @SuppressLint("CustomX509TrustManager") object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                @Suppress("UNCHECKED_CAST")
                captured = chain as Array<X509Certificate>
                // Se rechaza siempre: esta conexión solo sirve para leer la cadena.
                throw java.security.cert.CertificateException("captured")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturing), SecureRandom()) }
        try {
            (ssl.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                // SNI solo con nombres: no admite direcciones IP.
                if (host.any { it.isLetter() } && !host.contains(':')) {
                    runCatching {
                        val params = socket.sslParameters
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                        socket.sslParameters = params
                    }
                }
                socket.startHandshake()
            }
        } catch (_: Exception) {
            // Esperado: el gestor de confianza rechaza la conexión tras capturar la cadena.
        }
        captured?.toList()
    }
}
