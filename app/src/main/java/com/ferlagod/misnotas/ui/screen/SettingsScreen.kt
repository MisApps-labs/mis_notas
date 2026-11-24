// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferlagod.misnotas.BuildConfig
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.backup.BackupManager
import com.ferlagod.misnotas.data.local.pref.AppSettings
import com.ferlagod.misnotas.data.local.pref.AppSettingsState
import com.ferlagod.misnotas.data.local.pref.NextcloudCredentials
import com.ferlagod.misnotas.data.remote.http.TrustedCertificates
import com.ferlagod.misnotas.ui.viewmodel.DiagnosticsState
import com.ferlagod.misnotas.ui.viewmodel.LoginFlowState
import com.ferlagod.misnotas.data.local.pref.SortOrder
import com.ferlagod.misnotas.data.local.pref.ThemeMode
import com.ferlagod.misnotas.data.sync.SyncState
import com.ferlagod.misnotas.ui.theme.AccentGreen
import com.ferlagod.misnotas.ui.theme.AccentRed
import com.ferlagod.misnotas.util.AppLanguage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isConfigured: Boolean,
    currentCredentials: NextcloudCredentials?,
    syncState: SyncState,
    lastSync: Long?,
    settings: AppSettingsState,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    isAppLockAvailable: Boolean,
    onBack: () -> Unit,
    loginFlow: LoginFlowState,
    diagnostics: DiagnosticsState,
    trustedCerts: List<TrustedCertificates.Info>,
    accountActions: AccountActions,
    onDisconnectNextcloud: () -> Unit,
    onTriggerSync: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onGridLayoutChange: (Boolean) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onAutoPurgeChange: (Boolean) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (List<Uri>) -> Unit,
    /** Fecha de la copia previa a la conexión (null si no hay) y cómo sacarla de la app. */
    safetyCopyTime: Long?,
    onSaveSafetyCopy: (Uri) -> Unit,
    onAutoBackupChange: (Boolean) -> Unit,
    onAutoBackupFolderSelected: (Uri) -> Unit,
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onExport(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImport(uris)
    }
    val safetyCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onSaveSafetyCopy(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onAutoBackupFolderSelected(uri)
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = READABLE_MAX_WIDTH)
                .padding(horizontal = 16.dp)
        ) {
            // --- Apariencia ---
            SectionTitle(stringResource(R.string.section_appearance))
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    RowLabel(Icons.Rounded.DarkMode, stringResource(R.string.theme))
                    Spacer(modifier = Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                            ) {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> R.string.theme_system
                                            ThemeMode.LIGHT -> R.string.theme_light
                                            ThemeMode.DARK -> R.string.theme_dark
                                        }
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Divider()
                SwitchRow(
                    icon = Icons.Rounded.Contrast,
                    title = stringResource(R.string.amoled_mode),
                    subtitle = stringResource(R.string.amoled_mode_desc),
                    checked = settings.amoled,
                    onCheckedChange = onAmoledChange
                )
                Divider()
                SwitchRow(
                    icon = Icons.Rounded.GridView,
                    title = stringResource(R.string.grid_view),
                    subtitle = stringResource(R.string.grid_view_desc),
                    checked = settings.gridLayout,
                    onCheckedChange = onGridLayoutChange
                )
                Divider()
                SortRow(current = settings.sortOrder, onSelect = onSortOrderChange)
            }

            // --- Privacidad ---
            SectionTitle(stringResource(R.string.section_privacy))
            SettingsCard {
                SwitchRow(
                    icon = Icons.Rounded.Fingerprint,
                    title = stringResource(R.string.app_lock),
                    subtitle = if (isAppLockAvailable) stringResource(R.string.app_lock_desc) else stringResource(R.string.app_lock_unavailable),
                    checked = settings.appLock && isAppLockAvailable,
                    enabled = isAppLockAvailable,
                    onCheckedChange = onAppLockChange
                )
            }

            // --- Notas ---
            SectionTitle(stringResource(R.string.section_notes))
            SettingsCard {
                SwitchRow(
                    icon = Icons.Rounded.AutoDelete,
                    title = stringResource(R.string.auto_purge_trash),
                    subtitle = stringResource(R.string.auto_purge_trash_desc, AppSettings.TRASH_RETENTION_DAYS),
                    checked = settings.autoPurgeTrash,
                    onCheckedChange = onAutoPurgeChange
                )
            }

            // --- Copia de seguridad ---
            SectionTitle(stringResource(R.string.section_backup))
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch(backupFileName()) },
                            enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.export_notes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")
                                )
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.import_notes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (safetyCopyTime != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.safety_copy_desc, formatSyncTime(safetyCopyTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { safetyCopyLauncher.launch(safetyCopyFileName(safetyCopyTime)) },
                            enabled = !busy
                        ) {
                            Text(stringResource(R.string.save_safety_copy))
                        }
                    }
                    AnimatedVisibility(visible = busy) {
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_in_progress), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Divider()
                SwitchRow(
                    icon = Icons.Rounded.Backup,
                    title = stringResource(R.string.auto_backup),
                    subtitle = autoBackupStatus(settings),
                    checked = settings.autoBackup && settings.autoBackupFolder != null,
                    onCheckedChange = { enabled ->
                        // Sin carpeta elegida, activar la opción abre primero el selector.
                        if (enabled && settings.autoBackupFolder == null) folderLauncher.launch(null)
                        else onAutoBackupChange(enabled)
                    }
                )
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = false, role = Role.Button, onClick = { folderLauncher.launch(null) })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingIcon(Icons.Rounded.FolderOpen)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_backup_folder), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                        Text(
                            settings.autoBackupFolder?.let { folderLabel(it) } ?: stringResource(R.string.auto_backup_folder_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // --- Sincronización ---
            SectionTitle(stringResource(R.string.section_sync))
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    NextcloudSection(
                        isConfigured = isConfigured,
                        currentCredentials = currentCredentials,
                        syncState = syncState,
                        lastSync = lastSync,
                        loginFlow = loginFlow,
                        diagnostics = diagnostics,
                        trustedCerts = trustedCerts,
                        accountActions = accountActions,
                        onDisconnectNextcloud = onDisconnectNextcloud,
                        onTriggerSync = onTriggerSync
                    )
                }
            }

            // --- Idioma ---
            SectionTitle(stringResource(R.string.section_language))
            SettingsCard {
                LanguageSection(current = currentLanguage, onSelected = onLanguageSelected)
            }

            // --- Acerca de ---
            SectionTitle(stringResource(R.string.section_about))
            SettingsCard { AboutSection() }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun autoBackupStatus(settings: AppSettingsState): String {
    val base = pluralStringResource(R.plurals.auto_backup_desc, BackupManager.AUTO_BACKUP_KEEP, BackupManager.AUTO_BACKUP_KEEP)
    val status = when {
        settings.autoBackupFailed -> stringResource(R.string.auto_backup_failed)
        settings.lastAutoBackup > 0 -> stringResource(R.string.auto_backup_last, formatSyncTime(settings.lastAutoBackup))
        else -> null
    }
    return if (status == null) base else "$base\n$status"
}

/** Ruta legible de una carpeta SAF ("primary:Documents/Copias" → "Documents/Copias"). */
private fun folderLabel(treeUri: String): String = try {
    val docId = DocumentsContract.getTreeDocumentId(treeUri.toUri())
    val path = docId.substringAfter(':', docId)
    path.ifBlank { docId.substringBefore(':') }
} catch (_: Exception) {
    treeUri
}

private fun safetyCopyFileName(time: Long): String =
    BackupManager.SAFETY_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(time)) + ".zip"

private fun backupFileName(): String =
    "MisNotas-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 56.dp)
    )
}

@Composable
private fun RowLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingIcon(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SortRow(current: SortOrder, onSelect: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = false, role = Role.DropdownList, onClick = { expanded = true })
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(Icons.AutoMirrored.Rounded.Sort)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sort_by), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text(stringResource(current.labelRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.labelRes())) },
                    leadingIcon = { RadioButton(selected = order == current, onClick = null) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Nextcloud
// ----------------------------------------------------------------------

@Composable
private fun NextcloudSection(
    isConfigured: Boolean,
    currentCredentials: NextcloudCredentials?,
    syncState: SyncState,
    lastSync: Long?,
    loginFlow: LoginFlowState,
    diagnostics: DiagnosticsState,
    trustedCerts: List<TrustedCertificates.Info>,
    accountActions: AccountActions,
    onDisconnectNextcloud: () -> Unit,
    onTriggerSync: () -> Unit
) {
    val msgConnected = stringResource(R.string.account_connected_syncing)
    val msgSyncStarted = stringResource(R.string.sync_started)
    val msgDisconnected = stringResource(R.string.disconnected_message)
    val primary = MaterialTheme.colorScheme.primary
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // Con la cuenta ya conectada el formulario se pliega para no estorbar.
    var editing by remember(isConfigured) { mutableStateOf(!isConfigured) }

    // Estado de la conexión
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isConfigured) AccentGreen.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isConfigured) Icons.Rounded.CloudSync else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (isConfigured) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isConfigured) stringResource(R.string.nextcloud_connected) else stringResource(R.string.local_mode),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = if (isConfigured) "${currentCredentials?.username.orEmpty()} • ${currentCredentials?.serverUrl.orEmpty()}"
                else stringResource(R.string.local_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (isConfigured) {
                val statusText = when (syncState) {
                    is SyncState.Syncing -> stringResource(syncState.stage.labelRes)
                    is SyncState.Error -> stringResource(R.string.status_error, syncState.message)
                    is SyncState.Success -> stringResource(R.string.status_synced, formatSyncTime(syncState.timestamp)) +
                        (if (syncState.warnings > 0) " " + pluralStringResource(R.plurals.sync_warnings, syncState.warnings, syncState.warnings) else "") +
                        (if (syncState.protectedNotes > 0) " " + pluralStringResource(R.plurals.sync_protected_notes, syncState.protectedNotes, syncState.protectedNotes) else "")
                    SyncState.Idle -> lastSync?.let { stringResource(R.string.status_last_sync, formatSyncTime(it)) }
                        ?: stringResource(R.string.status_pending)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState is SyncState.Error) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (isConfigured) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    onTriggerSync()
                    successMessage = msgSyncStarted
                },
                enabled = syncState !is SyncState.Syncing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.sync_now), maxLines = 1)
            }
            OutlinedButton(
                onClick = { showDisconnectConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.disconnect), maxLines = 1)
            }
        }
        if (!editing) {
            TextButton(onClick = { editing = true }) {
                Text(stringResource(R.string.edit_account), color = primary)
            }
        }

        AnimatedVisibility(visible = showDisconnectConfirm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(AccentRed.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.disconnect_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = AccentRed
                )
                Text(text = stringResource(R.string.disconnect_message), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showDisconnectConfirm = false }) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            showDisconnectConfirm = false
                            onDisconnectNextcloud()
                            errorMessage = null
                            successMessage = msgDisconnected
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.disconnect_confirm))
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = editing) {
        Column {
            Spacer(modifier = Modifier.height(16.dp))
            // Se vuelve a crear al cambiar de cuenta para que tome los datos guardados.
            androidx.compose.runtime.key(currentCredentials) {
                AccountForm(
                    initial = currentCredentials,
                    loginFlow = loginFlow,
                    diagnostics = diagnostics,
                    trustedCerts = trustedCerts,
                    submitLabel = if (isConfigured) stringResource(R.string.update_and_sync) else stringResource(R.string.connect_and_encrypt),
                    actions = accountActions,
                    onConnected = {
                        editing = false
                        successMessage = msgConnected
                    }
                )
            }
        }
    }

    errorMessage?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    successMessage?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = AccentGreen)
    }
}

private fun formatSyncTime(timestamp: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMjmm"), locale).format(Date(timestamp))
}

// ----------------------------------------------------------------------
// Idioma y Acerca de
// ----------------------------------------------------------------------

@Composable
private fun LanguageSection(current: AppLanguage, onSelected: (AppLanguage) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        AppLanguage.entries.forEachIndexed { index, language ->
            val label = if (language == AppLanguage.SYSTEM) stringResource(R.string.language_system) else language.nativeName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = language == current,
                        onClick = { if (language != current) onSelected(language) },
                        role = Role.RadioButton
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (index == 0) SettingIcon(Icons.Rounded.Language) else Spacer(modifier = Modifier.width(26.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp)
                )
                RadioButton(selected = language == current, onClick = null)
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row {
            TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
                Text(stringResource(R.string.source_code))
            }
            TextButton(onClick = { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }) {
                Text(stringResource(R.string.license))
            }
        }
    }
}

private const val REPO_URL = "https://github.com/MisApps-labs/mis_notas"
