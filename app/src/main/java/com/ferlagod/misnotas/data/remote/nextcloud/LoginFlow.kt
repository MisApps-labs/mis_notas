// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.remote.nextcloud

import com.ferlagod.misnotas.data.local.pref.ServerUrl
import com.ferlagod.misnotas.data.remote.webdav.WebDavException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Inicio de sesión de Nextcloud en el navegador ("Login Flow v2").
 *
 * 1. [start] pide al servidor una sesión de inicio y devuelve la dirección que hay que abrir en
 *    el navegador. Allí el usuario entra con su cuenta (también con 2FA o SSO) y autoriza la app.
 * 2. [poll] pregunta cada pocos segundos hasta que el servidor entrega una contraseña de
 *    aplicación nueva, exclusiva para este dispositivo y revocable desde Nextcloud.
 *
 * Así el usuario no tiene que crear ni copiar la contraseña de aplicación a mano.
 * Referencia: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/
 */
class LoginFlow(private val http: OkHttpClient) {

    data class Session(val loginUrl: String, val pollEndpoint: String, val token: String)

    data class Result(val server: String, val loginName: String, val appPassword: String)

    /** El servidor no ofrece Login Flow v2 (no es Nextcloud, o es muy antiguo). */
    class NotSupportedException : Exception("Login Flow v2 not available")

    suspend fun start(serverUrl: String): Session = withContext(Dispatchers.IO) {
        val base = ServerUrl.normalize(serverUrl).substringBefore("/remote.php").trimEnd('/')
        require(ServerUrl.isAllowed(base)) { "Unencrypted HTTP is only allowed on the local network" }
        val request = Request.Builder()
            .url("$base/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", DEVICE_NAME)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 405) throw NotSupportedException()
            if (!response.isSuccessful) throw WebDavException(response.code, "Login flow failed (${response.code})")
            val json = try {
                JSONObject(response.body?.string().orEmpty())
            } catch (_: Exception) {
                throw NotSupportedException()
            }
            val poll = json.optJSONObject("poll") ?: throw NotSupportedException()
            Session(
                loginUrl = json.getString("login"),
                pollEndpoint = poll.getString("endpoint"),
                token = poll.getString("token")
            )
        }
    }

    /**
     * Espera a que el usuario autorice la app en el navegador. Se cancela con la corrutina.
     * El servidor invalida la sesión a los 20 minutos.
     */
    suspend fun poll(session: Session, intervalMs: Long = 2_000L, timeoutMs: Long = 20 * 60_000L): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pollOnce(session)?.let { return it }
            delay(intervalMs)
        }
        throw java.util.concurrent.TimeoutException("Login flow expired")
    }

    private suspend fun pollOnce(session: Session): Result? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.pollEndpoint)
            .post(FormBody.Builder().add("token", session.token).build())
            .header("User-Agent", DEVICE_NAME)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                // 404 mientras el usuario no ha terminado.
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string().orEmpty())
                Result(
                    server = json.getString("server").trimEnd('/'),
                    loginName = json.getString("loginName"),
                    appPassword = json.getString("appPassword")
                )
            }
        } catch (_: java.io.IOException) {
            // Un corte de red momentáneo no debe cancelar el inicio de sesión.
            null
        }
    }

    companion object {
        /** Nombre con el que aparece el dispositivo en Nextcloud → Seguridad → Dispositivos. */
        val DEVICE_NAME: String = "Mis Notas (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})"
            .replace(Regex("[^\\x20-\\x7E]"), "")
    }
}
