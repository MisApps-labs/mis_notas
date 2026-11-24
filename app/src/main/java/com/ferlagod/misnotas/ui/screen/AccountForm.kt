// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.local.pref.NextcloudCredentials
import com.ferlagod.misnotas.data.local.pref.ServerKind
import com.ferlagod.misnotas.data.local.pref.ServerUrl
import com.ferlagod.misnotas.data.remote.http.TrustedCertificates
import com.ferlagod.misnotas.data.remote.webdav.ConnectionDiagnostics
import com.ferlagod.misnotas.data.remote.webdav.ConnectionDiagnostics.Issue
import com.ferlagod.misnotas.data.remote.webdav.ConnectionDiagnostics.Status
import com.ferlagod.misnotas.data.remote.webdav.ConnectionDiagnostics.Step
import com.ferlagod.misnotas.ui.viewmodel.DiagnosticsState
import com.ferlagod.misnotas.ui.viewmodel.LoginFlowState
import java.security.cert.X509Certificate

/** Acciones del formulario de cuenta (las implementa el ViewModel). */
class AccountActions(
    val startLogin: (serverUrl: String) -> Unit,
    val cancelLogin: () -> Unit,
    val test: (kind: ServerKind, serverUrl: String, user: String, password: String) -> Unit,
    val dismissDiagnostics: () -> Unit,
    val trustCertificate: (X509Certificate) -> Unit,
    val importCertificate: (Uri) -> Unit,
    val removeCertificate: (sha256: String) -> Unit,
    val submit: (
        kind: ServerKind, serverUrl: String, user: String, password: String, master: String,
        onSuccess: () -> Unit, onError: (String) -> Unit
    ) -> Unit
)

/**
 * Formulario para conectar una cuenta: Nextcloud/ownCloud (con inicio de sesión en el navegador
 * o contraseña de aplicación) o cualquier servidor WebDAV, prueba de conexión y certificados
 * propios. Lo comparten la pantalla de bienvenida y Ajustes.
 */
@Composable
fun AccountForm(
    initial: NextcloudCredentials?,
    loginFlow: LoginFlowState,
    diagnostics: DiagnosticsState,
    trustedCerts: List<TrustedCertificates.Info>,
    submitLabel: String,
    actions: AccountActions,
    onConnected: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val primary = MaterialTheme.colorScheme.primary

    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: ServerKind.NEXTCLOUD) }
    var serverUrl by rememberSaveable { mutableStateOf(initial?.serverUrl ?: "https://") }
    var username by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var appPassword by rememberSaveable { mutableStateOf(initial?.appPassword ?: "") }
    var masterPassword by rememberSaveable { mutableStateOf("") }
    // Nextcloud: los campos de usuario y contraseña de aplicación solo se muestran si el usuario
    // prefiere escribirlos (o ya los tenía).
    var manual by rememberSaveable { mutableStateOf(initial != null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var masterVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCerts by rememberSaveable { mutableStateOf(false) }

    fun clearMessages() {
        errorMessage = null
        if (diagnostics !is DiagnosticsState.Idle) actions.dismissDiagnostics()
    }

    // Credenciales recibidas del navegador.
    LaunchedEffect(loginFlow) {
        if (loginFlow is LoginFlowState.Done) {
            serverUrl = loginFlow.serverUrl
            username = loginFlow.username
            appPassword = loginFlow.appPassword
        }
    }

    val certLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.importCertificate(uri)
    }

    Column {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ServerKind.entries.forEachIndexed { index, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = {
                        if (kind != k) {
                            kind = k
                            clearMessages()
                            actions.cancelLogin()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ServerKind.entries.size)
                ) {
                    Text(
                        stringResource(if (k == ServerKind.NEXTCLOUD) R.string.server_kind_nextcloud else R.string.server_kind_webdav),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        FormField(
            value = serverUrl,
            onValueChange = { serverUrl = it; clearMessages() },
            placeholder = stringResource(if (kind == ServerKind.NEXTCLOUD) R.string.server_hint else R.string.webdav_folder_hint),
            icon = Icons.Rounded.Storage,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
        )
        Hint(stringResource(if (kind == ServerKind.NEXTCLOUD) R.string.server_help_nextcloud else R.string.server_help_webdav))
        if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
            val local = ServerUrl.isLocalHost(ServerUrl.host(serverUrl))
            Hint(
                stringResource(if (local) R.string.http_local_warning else R.string.err_https_required),
                color = if (local) WarningColor else MaterialTheme.colorScheme.error
            )
        }

        val loggedIn = kind == ServerKind.NEXTCLOUD && loginFlow is LoginFlowState.Done
        if (kind == ServerKind.NEXTCLOUD && !manual) {
            Spacer(Modifier.height(12.dp))
            when (loginFlow) {
                is LoginFlowState.Done -> StatusLine(
                    Icons.Rounded.CheckCircle, SuccessColor,
                    stringResource(R.string.login_flow_done, loginFlow.username)
                )
                is LoginFlowState.Waiting, LoginFlowState.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.login_flow_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        if (loginFlow is LoginFlowState.Waiting) {
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            TextButton(onClick = { runCatching { uriHandler.openUri(loginFlow.loginUrl) } }) {
                                Text(stringResource(R.string.login_flow_reopen))
                            }
                        }
                        TextButton(onClick = actions.cancelLogin) { Text(stringResource(R.string.cancel)) }
                    }
                }
                else -> {
                    FilledTonalButton(
                        onClick = { focusManager.clearFocus(); errorMessage = null; actions.startLogin(serverUrl) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.login_flow_button))
                    }
                    if (loginFlow is LoginFlowState.Error) Hint(loginFlow.message, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { manual = true; actions.cancelLogin() }) {
                Text(stringResource(R.string.login_flow_manual), style = MaterialTheme.typography.bodySmall)
            }
        }

        AnimatedVisibility(visible = kind == ServerKind.WEBDAV || manual) {
            Column {
                Spacer(Modifier.height(10.dp))
                FormField(
                    value = username,
                    onValueChange = { username = it; clearMessages() },
                    placeholder = stringResource(R.string.username_hint),
                    icon = Icons.Rounded.Person,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                )
                Spacer(Modifier.height(10.dp))
                FormField(
                    value = appPassword,
                    onValueChange = { appPassword = it; clearMessages() },
                    placeholder = stringResource(if (kind == ServerKind.NEXTCLOUD) R.string.app_password_hint else R.string.webdav_password_hint),
                    icon = Icons.Rounded.Key,
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
                )
                if (kind == ServerKind.NEXTCLOUD) {
                    Hint(stringResource(R.string.app_password_help))
                    TextButton(onClick = { manual = false }) {
                        Text(stringResource(R.string.login_flow_use_browser), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // --- Cifrado ---
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.section_e2ee),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = primary
            )
        }
        Hint(stringResource(R.string.e2ee_desc))
        Spacer(Modifier.height(8.dp))
        FormField(
            value = masterPassword,
            onValueChange = { masterPassword = it; errorMessage = null },
            placeholder = stringResource(R.string.master_password_hint),
            icon = Icons.Rounded.Lock,
            isPassword = true,
            passwordVisible = masterVisible,
            onTogglePassword = { masterVisible = !masterVisible },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )

        // --- Prueba de conexión ---
        Spacer(Modifier.height(14.dp))
        val canTest = serverUrl.isNotBlank() && serverUrl.trim() != "https://" && username.isNotBlank() && appPassword.isNotBlank()
        OutlinedButton(
            onClick = { focusManager.clearFocus(); actions.test(kind, serverUrl, username, appPassword) },
            enabled = canTest && diagnostics !is DiagnosticsState.Running,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (diagnostics is DiagnosticsState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.test_connection))
        }
        if (diagnostics is DiagnosticsState.Done) {
            DiagnosticsReport(diagnostics.report, onTrust = { actions.trustCertificate(it); actions.test(kind, serverUrl, username, appPassword) })
        }

        // --- Certificados ---
        TextButton(onClick = { showCerts = !showCerts }) {
            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (trustedCerts.isEmpty()) stringResource(R.string.trusted_certs)
                else stringResource(R.string.trusted_certs_count, trustedCerts.size),
                style = MaterialTheme.typography.bodySmall
            )
        }
        AnimatedVisibility(visible = showCerts) {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Hint(stringResource(R.string.trusted_certs_desc))
                trustedCerts.forEach { cert ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cnOf(cert.subject), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                TrustedCertificates.formatFingerprint(cert.sha256).take(29) + "…",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { actions.removeCertificate(cert.sha256) }) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                TextButton(onClick = {
                    certLauncher.launch(arrayOf("application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert", "application/octet-stream", "*/*"))
                }) {
                    Text(stringResource(R.string.import_certificate))
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        val ready = canTest && masterPassword.isNotBlank() && (kind == ServerKind.WEBDAV || manual || loggedIn)
        Button(
            onClick = {
                focusManager.clearFocus()
                isLoading = true
                errorMessage = null
                actions.submit(kind, serverUrl.trim(), username.trim(), appPassword.trim(), masterPassword, {
                    isLoading = false
                    masterPassword = ""
                    onConnected()
                }, { error ->
                    isLoading = false
                    errorMessage = error
                })
            },
            enabled = ready && !isLoading,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(submitLabel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

private val SuccessColor = Color(0xFF3F8F4F)
private val WarningColor = Color(0xFFB7791F)

@Composable
private fun DiagnosticsReport(report: ConnectionDiagnostics.Report, onTrust: (X509Certificate) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when {
                !report.ok -> stringResource(R.string.diag_summary_failed)
                report.warnings > 0 -> stringResource(R.string.diag_summary_warnings)
                else -> stringResource(R.string.diag_summary_ok)
            },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        report.steps.filter { it.status != Status.SKIPPED || it.step == Step.REACH }.forEach { r ->
            val (icon, tint) = when (r.status) {
                Status.OK -> Icons.Rounded.CheckCircle to SuccessColor
                Status.WARNING -> Icons.Rounded.Warning to WarningColor
                Status.FAILED -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                Status.SKIPPED -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(stepTitle(r.step)), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    r.issue?.let { issue ->
                        Text(
                            stringResource(issueText(issue), r.detail ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        report.untrustedChain?.firstOrNull()?.let { cert ->
            val info = TrustedCertificates.info(cert)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(stringResource(R.string.cert_untrusted_title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.cert_subject, cnOf(info.subject)), style = MaterialTheme.typography.bodySmall)
                if (!info.selfSigned) Text(stringResource(R.string.cert_issuer, cnOf(info.issuer)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.bodySmall)
                Text(
                    TrustedCertificates.formatFingerprint(info.sha256),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                )
                Hint(stringResource(R.string.cert_trust_explain))
                // Se confía en el último de la cadena que presenta el servidor: si es autofirmado
                // es el propio certificado; si viene con su CA, la CA (sobrevive a renovaciones).
                val anchor = report.untrustedChain.last()
                TextButton(onClick = { onTrust(anchor) }) { Text(stringResource(R.string.cert_trust_button)) }
            }
        }
    }
}

private fun cnOf(dn: String): String =
    Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn

private fun stepTitle(step: Step): Int = when (step) {
    Step.ADDRESS -> R.string.diag_step_address
    Step.REACH -> R.string.diag_step_reach
    Step.AUTH -> R.string.diag_step_auth
    Step.FOLDER -> R.string.diag_step_folder
    Step.WRITE -> R.string.diag_step_write
    Step.CONDITIONAL -> R.string.diag_step_conditional
    Step.LIST -> R.string.diag_step_list
    Step.CONTENTS -> R.string.diag_step_contents
}

private fun issueText(issue: Issue): Int = when (issue) {
    Issue.INVALID_URL -> R.string.diag_invalid_url
    Issue.HTTP_NOT_LOCAL -> R.string.err_https_required
    Issue.HTTP_LOCAL -> R.string.http_local_warning
    Issue.DNS -> R.string.diag_dns
    Issue.UNREACHABLE -> R.string.diag_unreachable
    Issue.TIMEOUT -> R.string.diag_timeout
    Issue.CERT_UNTRUSTED -> R.string.diag_cert_untrusted
    Issue.CERT_HOSTNAME -> R.string.diag_cert_hostname
    Issue.TLS_OTHER -> R.string.diag_tls_other
    Issue.AUTH -> R.string.diag_auth
    Issue.NOT_WEBDAV -> R.string.diag_not_webdav
    Issue.PARENT_MISSING -> R.string.diag_parent_missing
    Issue.HTTP_ERROR -> R.string.diag_http_error
    Issue.NO_ETAG -> R.string.diag_no_etag
    Issue.IF_MATCH_IGNORED -> R.string.diag_if_match_ignored
    Issue.IF_NONE_MATCH_IGNORED -> R.string.diag_if_none_match_ignored
    Issue.FOREIGN_FILES -> R.string.diag_foreign_files
    Issue.EXISTING_VAULT -> R.string.diag_existing_vault
    Issue.EMPTY_FOLDER -> R.string.diag_empty_folder
}

@Composable
private fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
}

@Composable
private fun StatusLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth()
    )
}
