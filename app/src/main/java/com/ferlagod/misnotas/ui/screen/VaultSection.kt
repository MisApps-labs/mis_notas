// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.ui.viewmodel.MainViewModel

/** Acciones sobre la bóveda (las implementa el ViewModel). */
class VaultActions(
    val upgrade: (password: String, withRecovery: Boolean, onDone: (String?) -> Unit, onError: (String) -> Unit) -> Unit,
    val changePassword: (old: String, new: String, onDone: () -> Unit, onError: (String) -> Unit) -> Unit,
    val createRecovery: (onDone: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    val rotate: (onDone: () -> Unit, onError: (String) -> Unit) -> Unit
)

private enum class VaultDialog { UPGRADE, PASSWORD, RECOVERY_CONFIRM, ROTATE }

/** Ajustes → Sincronización → Cifrado: formato de la bóveda y sus operaciones. */
@Composable
fun VaultSection(state: MainViewModel.VaultInfoState, actions: VaultActions) {
    var dialog by remember { mutableStateOf<VaultDialog?>(null) }
    var shownRecovery by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val msgPasswordChanged = stringResource(R.string.vault_password_changed)
    val msgRotated = stringResource(R.string.vault_rotated)
    val msgUpgraded = stringResource(R.string.vault_upgraded)

    fun fail(e: String) { busy = false; error = e }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (state.v3) R.string.vault_v3_desc else R.string.vault_v2_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.v3) {
            Text(
                stringResource(if (state.hasRecovery) R.string.vault_recovery_yes else R.string.vault_recovery_no),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hasRecovery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(10.dp))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (!state.v3) {
            OutlinedButton(onClick = { error = null; dialog = VaultDialog.UPGRADE }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vault_upgrade))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { error = null; dialog = VaultDialog.PASSWORD }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vault_change_password))
                }
                OutlinedButton(onClick = { error = null; dialog = VaultDialog.RECOVERY_CONFIRM }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.hasRecovery) R.string.vault_recovery_new else R.string.vault_recovery_create))
                }
                OutlinedButton(onClick = { error = null; dialog = VaultDialog.ROTATE }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vault_rotate))
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = primary, modifier = Modifier.padding(top = 8.dp)) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }

    when (dialog) {
        VaultDialog.UPGRADE -> {
            var password by remember { mutableStateOf("") }
            var withRecovery by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.vault_upgrade)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.vault_upgrade_explain), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.vault_upgrade_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        PasswordField(password, { password = it }, stringResource(R.string.master_password_hint))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withRecovery, onCheckedChange = { withRecovery = it })
                            Text(stringResource(R.string.vault_upgrade_with_recovery), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = password.isNotEmpty(), onClick = {
                        dialog = null; busy = true; error = null; message = null
                        actions.upgrade(password, withRecovery, { key -> busy = false; message = msgUpgraded; shownRecovery = key }, ::fail)
                    }) { Text(stringResource(R.string.vault_upgrade_confirm)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
        VaultDialog.PASSWORD -> {
            var old by remember { mutableStateOf("") }
            var new by remember { mutableStateOf("") }
            var repeat by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.vault_change_password)) },
                text = {
                    Column {
                        PasswordField(old, { old = it }, stringResource(R.string.vault_current_password))
                        Spacer(Modifier.height(8.dp))
                        PasswordField(new, { new = it }, stringResource(R.string.vault_new_password))
                        Spacer(Modifier.height(8.dp))
                        PasswordField(repeat, { repeat = it }, stringResource(R.string.vault_repeat_password))
                        if (repeat.isNotEmpty() && new != repeat) {
                            Text(stringResource(R.string.vault_passwords_differ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(stringResource(R.string.vault_change_password_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    TextButton(enabled = old.isNotEmpty() && new.length >= MIN_PASSWORD && new == repeat, onClick = {
                        dialog = null; busy = true; error = null; message = null
                        actions.changePassword(old, new, { busy = false; message = msgPasswordChanged }, ::fail)
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
        VaultDialog.RECOVERY_CONFIRM -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (state.hasRecovery) R.string.vault_recovery_new else R.string.vault_recovery_create)) },
            text = { Text(stringResource(R.string.vault_recovery_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true; error = null; message = null
                    actions.createRecovery({ key -> busy = false; shownRecovery = key }, ::fail)
                }) { Text(stringResource(R.string.vault_recovery_generate)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
        )
        VaultDialog.ROTATE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.vault_rotate)) },
            text = { Text(stringResource(R.string.vault_rotate_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true; error = null; message = null
                    actions.rotate({ busy = false; message = msgRotated }, ::fail)
                }) { Text(stringResource(R.string.vault_rotate)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
        )
        null -> Unit
    }

    shownRecovery?.let { key -> RecoveryKeyDialog(key) { shownRecovery = null } }
}

/** Enseña la clave de recuperación una sola vez, para que el usuario la guarde. */
@Composable
fun RecoveryKeyDialog(key: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_recovery_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_recovery_save), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        key,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    )
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(key)); copied = true }) {
                    Text(stringResource(if (copied) R.string.copied_to_clipboard else R.string.vault_recovery_copy))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_recovery_saved)) } }
    )
}

/** Pantalla de desbloqueo: entrar con la clave de recuperación y elegir una contraseña nueva. */
@Composable
fun RecoverDialog(onRecover: (key: String, newPassword: String, onError: (String) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_recover_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_recover_desc), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                FormField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    placeholder = "XXXX-XXXX-XXXX-XXXX-…",
                    icon = Icons.Rounded.Key,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(new, { new = it }, stringResource(R.string.vault_new_password))
                Spacer(Modifier.height(8.dp))
                PasswordField(repeat, { repeat = it }, stringResource(R.string.vault_repeat_password))
                if (repeat.isNotEmpty() && new != repeat) {
                    Text(stringResource(R.string.vault_passwords_differ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && key.isNotBlank() && new.length >= MIN_PASSWORD && new == repeat, onClick = {
                busy = true
                onRecover(key, new) { busy = false; error = it }
            }) { Text(stringResource(R.string.vault_recover_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private const val MIN_PASSWORD = 8

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var visible by remember { mutableStateOf(false) }
    FormField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        icon = Icons.Rounded.Lock,
        isPassword = true,
        passwordVisible = visible,
        onTogglePassword = { visible = !visible },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
}
