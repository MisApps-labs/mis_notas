// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

@file:Suppress("DEPRECATION") // security-crypto está deprecado pero no hay sustituto estable equivalente

package com.ferlagod.misnotas.data.local.pref

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ferlagod.misnotas.security.CryptoManager
import com.ferlagod.misnotas.security.Keyring
import javax.crypto.SecretKey

/**
 * Credenciales de Nextcloud / ownCloud.
 */
data class NextcloudCredentials(
    val serverUrl: String,
    val username: String,
    val appPassword: String,
    val kind: ServerKind = ServerKind.NEXTCLOUD
)

/**
 * Almacén seguro (EncryptedSharedPreferences, clave en Android Keystore) para credenciales,
 * parámetros de derivación y la clave de cifrado desbloqueada.
 */
class AuthPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    companion object {
        private const val TAG = "AuthPreferences"
        private const val PREFS_FILENAME = "misnotas_secure_auth_prefs"
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_USERNAME = "key_username"
        private const val KEY_APP_PASSWORD = "key_app_password"
        private const val KEY_SERVER_KIND = "key_server_kind"
        private const val KEY_SALT_BASE64 = "key_salt_base64"
        private const val KEY_ITERATIONS = "key_kdf_iterations"
        private const val KEY_CHECK = "key_check"
        private const val KEY_DERIVED_KEY_BASE64 = "key_derived_key_base64"
        private const val KEY_KEYRING_JSON = "key_keyring_json"
        private const val KEY_VMK_BASE64 = "key_vmk_base64"
        private const val KEY_KEYINFO_V3 = "key_keyinfo_v3"
        private const val KEY_REENCRYPT = "key_reencrypt_pending"
        private const val KEY_LAST_SYNC = "key_last_sync"
        const val DEFAULT_FOLDER_NAME = "MisNotas"

        @Volatile
        private var INSTANCE: AuthPreferences? = null

        fun getInstance(context: Context): AuthPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthPreferences(context).also { INSTANCE = it }
            }

        /** Intentos de abrir las preferencias antes de darlas por ilegibles. */
        private const val OPEN_ATTEMPTS = 3

        /**
         * Si el Keystore se ha perdido (p. ej. tras restaurar una copia de seguridad en otro
         * dispositivo), el fichero cifrado es ilegible: se borra y se crea de nuevo.
         *
         * El Keystore a veces falla de forma pasajera (justo tras arrancar el móvil, con el
         * servicio ocupado…). Borrar en ese caso cerraría la sesión y perdería el salt local, así
         * que antes se reintenta varias veces.
         */
        private fun createPrefs(context: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            var lastError: Exception? = null
            for (attempt in 1..OPEN_ATTEMPTS) {
                try {
                    return build()
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "No se pudieron abrir las preferencias seguras (intento $attempt)", e)
                    if (attempt < OPEN_ATTEMPTS) Thread.sleep(200L * attempt)
                }
            }
            Log.w(TAG, "Preferencias seguras ilegibles; se reinician", lastError)
            context.deleteSharedPreferences(PREFS_FILENAME)
            return build()
        }
    }

    // ------------------------------------------------------------------
    // Credenciales
    // ------------------------------------------------------------------

    fun saveCredentials(credentials: NextcloudCredentials) {
        prefs.edit {
            putString(KEY_SERVER_URL, credentials.serverUrl.trim().trimEnd('/'))
            putString(KEY_USERNAME, credentials.username.trim())
            putString(KEY_APP_PASSWORD, credentials.appPassword.trim())
            putString(KEY_SERVER_KIND, credentials.kind.key)
        }
    }

    fun getCredentials(): NextcloudCredentials? {
        val server = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val pass = prefs.getString(KEY_APP_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        return NextcloudCredentials(server, user, pass, ServerKind.fromKey(prefs.getString(KEY_SERVER_KIND, null)))
    }

    fun hasCredentials(): Boolean = getCredentials() != null

    fun clearCredentials() {
        prefs.edit {
            remove(KEY_SERVER_URL)
            remove(KEY_USERNAME)
            remove(KEY_APP_PASSWORD)
            remove(KEY_SERVER_KIND)
            remove(KEY_LAST_SYNC)
        }
    }

    // ------------------------------------------------------------------
    // Parámetros de la bóveda (salt, iteraciones, verificador)
    // ------------------------------------------------------------------

    fun getSalt(): ByteArray? {
        val saltBase64 = prefs.getString(KEY_SALT_BASE64, null) ?: return null
        return try {
            CryptoManager.saltFromBase64(saltBase64)
        } catch (_: Exception) {
            null
        }
    }

    fun getIterations(): Int = prefs.getInt(KEY_ITERATIONS, CryptoManager.LEGACY_PBKDF2_ITERATIONS)

    fun getKeyCheck(): String? = prefs.getString(KEY_CHECK, null)

    fun saveVaultParams(salt: ByteArray, iterations: Int, keyCheck: String?) {
        prefs.edit {
            putString(KEY_SALT_BASE64, CryptoManager.saltToBase64(salt))
            putInt(KEY_ITERATIONS, iterations)
            if (keyCheck != null) putString(KEY_CHECK, keyCheck) else remove(KEY_CHECK)
        }
    }

    fun clearVaultParams() {
        prefs.edit {
            remove(KEY_SALT_BASE64)
            remove(KEY_ITERATIONS)
            remove(KEY_CHECK)
            remove(KEY_KEYINFO_V3)
        }
    }

    /** Copia del `keyinfo.json` v3 (para desbloquear sin conexión y restaurarlo si desaparece). */
    fun getKeyInfoV3(): String? = prefs.getString(KEY_KEYINFO_V3, null)

    fun saveKeyInfoV3(raw: String?) {
        prefs.edit { if (raw != null) putString(KEY_KEYINFO_V3, raw) else remove(KEY_KEYINFO_V3) }
    }

    // ------------------------------------------------------------------
    // Clave desbloqueada
    // ------------------------------------------------------------------

    fun saveDerivedKey(secretKey: SecretKey) {
        prefs.edit { putString(KEY_DERIVED_KEY_BASE64, CryptoManager.keyToBase64(secretKey)) }
    }

    fun getDerivedKey(): SecretKey? {
        val keyBase64 = prefs.getString(KEY_DERIVED_KEY_BASE64, null) ?: return null
        return try {
            CryptoManager.keyFromBase64(keyBase64)
        } catch (_: Exception) {
            null
        }
    }

    fun clearDerivedKey() {
        prefs.edit {
            remove(KEY_DERIVED_KEY_BASE64)
            remove(KEY_KEYRING_JSON)
            remove(KEY_VMK_BASE64)
        }
    }

    /** Guarda las claves desbloqueadas: la única de una bóveda v2 o el llavero y la VMK de una v3. */
    fun saveKeyring(ring: Keyring) {
        prefs.edit {
            val vmk = ring.vmk
            if (vmk == null) {
                putString(KEY_DERIVED_KEY_BASE64, CryptoManager.keyToBase64(ring.currentKey))
                remove(KEY_KEYRING_JSON)
                remove(KEY_VMK_BASE64)
            } else {
                putString(KEY_KEYRING_JSON, ring.toJson().toString())
                putString(KEY_VMK_BASE64, CryptoManager.keyToBase64(vmk))
                // La clave v2 sigue a mano por si hace falta (es la "v2" del llavero).
                ring.key(Keyring.LEGACY_KID)?.let { putString(KEY_DERIVED_KEY_BASE64, CryptoManager.keyToBase64(it)) }
            }
        }
    }

    fun getKeyring(): Keyring? {
        val ringJson = prefs.getString(KEY_KEYRING_JSON, null)
        val vmk = prefs.getString(KEY_VMK_BASE64, null)
        if (ringJson != null && vmk != null) {
            return try {
                Keyring.fromJson(org.json.JSONObject(ringJson), CryptoManager.keyFromBase64(vmk))
            } catch (_: Exception) {
                null
            }
        }
        return getDerivedKey()?.let { Keyring.legacy(it) }
    }

    fun isKeyUnlocked(): Boolean = getKeyring() != null

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    fun setLastSync(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, timestamp) }
    }

    /** Hay que volver a cifrar y subir todo con la clave actual (ver SyncRepository.reencryptAll). */
    fun isReencryptPending(): Boolean = prefs.getBoolean(KEY_REENCRYPT, false)

    fun setReencryptPending(pending: Boolean) {
        prefs.edit { putBoolean(KEY_REENCRYPT, pending) }
    }

    fun getLastSync(): Long? = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }

    /**
     * URL WebDAV de la carpeta de la app:
     * - Nextcloud/ownCloud: https://servidor/remote.php/dav/files/<usuario>/MisNotas/
     * - WebDAV genérico: la dirección de la carpeta tal cual.
     */
    fun getWebDavRootUrl(): String? = getCredentials()?.let { buildWebDavRootUrl(it) }

    fun buildWebDavRootUrl(creds: NextcloudCredentials): String {
        val rawServer = creds.serverUrl.trim().trimEnd('/')
        if (creds.kind == ServerKind.WEBDAV) return "$rawServer/"
        if (rawServer.contains("/remote.php/dav/files/")) return "$rawServer/"
        val user = java.net.URLEncoder.encode(creds.username, "UTF-8").replace("+", "%20")
        return "$rawServer/remote.php/dav/files/$user/$DEFAULT_FOLDER_NAME/"
    }

    fun clearAll() {
        prefs.edit { clear() }
    }
}
