// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.remote.http

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificados en los que el usuario ha decidido confiar además de los del sistema: el de un
 * servidor con certificado autofirmado o la CA de su red doméstica.
 *
 * Se guardan en formato DER dentro del almacenamiento privado de la app (no se copian a ningún
 * otro sitio). Un certificado añadido sirve como ancla de confianza: se aceptan las conexiones
 * cuya cadena termine en él, y el nombre del servidor se sigue comprobando como siempre.
 */
class TrustedCertificates(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    data class Info(
        val sha256: String,
        val subject: String,
        val issuer: String,
        val notAfter: Long,
        val selfSigned: Boolean
    )

    fun list(): List<X509Certificate> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> runCatching { parse(f.readBytes()).firstOrNull() }.getOrNull() }
            .orEmpty()

    fun infos(): List<Info> = list().map { info(it) }

    fun add(cert: X509Certificate) {
        dir.mkdirs()
        File(dir, fingerprint(cert) + EXT).writeBytes(cert.encoded)
        HttpClientProvider.invalidate()
    }

    fun remove(sha256: String) {
        File(dir, sha256.replace(":", "").lowercase() + EXT).delete()
        HttpClientProvider.invalidate()
    }

    fun isEmpty(): Boolean = list().isEmpty()

    companion object {
        private const val DIR_NAME = "trusted_certs"
        private const val EXT = ".der"

        /** Lee uno o varios certificados en PEM (`-----BEGIN CERTIFICATE-----`) o DER. */
        fun parse(bytes: ByteArray): List<X509Certificate> {
            val factory = CertificateFactory.getInstance("X.509")
            val certs = factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
            if (certs.isEmpty()) throw CertificateException("No certificate found")
            return certs
        }

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /** Huella legible: `AB:CD:…` en bloques de dos. */
        fun formatFingerprint(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

        fun info(cert: X509Certificate) = Info(
            sha256 = fingerprint(cert),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notAfter = cert.notAfter.time,
            selfSigned = cert.subjectX500Principal == cert.issuerX500Principal
        )

        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        fun trustManagerFor(certs: List<X509Certificate>): X509TrustManager? {
            if (certs.isEmpty()) return null
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> store.setCertificateEntry("user-$i", c) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/**
 * Acepta una cadena si la valida el sistema o, si no, alguno de los certificados del usuario.
 * Ambos son gestores estándar de la plataforma: aquí no se relaja ninguna comprobación.
 */
@SuppressLint("CustomX509TrustManager")
class CompositeTrustManager(
    private val system: X509TrustManager,
    private val user: X509TrustManager?
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemError: CertificateException) {
            val userManager = user ?: throw systemError
            try {
                userManager.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                throw systemError
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + (user?.acceptedIssuers ?: emptyArray())
}
