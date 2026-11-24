// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.ferlagod.misnotas.R
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.data.backup.BackupManager
import com.ferlagod.misnotas.data.local.AppDatabase
import com.ferlagod.misnotas.data.local.entity.AttachmentEntity
import com.ferlagod.misnotas.data.local.entity.FolderEntity
import com.ferlagod.misnotas.data.local.entity.NoteEntity
import com.ferlagod.misnotas.data.local.entity.NoteRevisionEntity
import com.ferlagod.misnotas.data.local.model.FolderTree
import com.ferlagod.misnotas.data.local.model.SyncStatus
import com.ferlagod.misnotas.data.local.pref.AppSettings
import com.ferlagod.misnotas.data.local.pref.AppSettingsState
import com.ferlagod.misnotas.data.local.pref.NextcloudCredentials
import com.ferlagod.misnotas.data.local.pref.ServerKind
import com.ferlagod.misnotas.data.local.pref.ServerUrl
import com.ferlagod.misnotas.data.remote.http.HttpClientProvider
import com.ferlagod.misnotas.data.remote.http.TrustedCertificates
import com.ferlagod.misnotas.data.remote.nextcloud.LoginFlow
import com.ferlagod.misnotas.data.remote.webdav.ConnectionDiagnostics
import com.ferlagod.misnotas.data.local.pref.SortOrder
import com.ferlagod.misnotas.data.local.pref.ThemeMode
import com.ferlagod.misnotas.data.sync.SyncMerger
import com.ferlagod.misnotas.data.sync.SyncRepository
import com.ferlagod.misnotas.data.sync.SyncState
import com.ferlagod.misnotas.data.sync.SyncWorker
import com.ferlagod.misnotas.data.backup.AutoBackupWorker
import com.ferlagod.misnotas.ui.markdown.markdownPlainText
import com.ferlagod.misnotas.ui.markdown.markdownPreview
import com.ferlagod.misnotas.ui.theme.NoteColor
import com.ferlagod.misnotas.util.ImageCompressor
import com.ferlagod.misnotas.util.LanguageManager
import com.ferlagod.misnotas.reminder.Reminders
import com.ferlagod.misnotas.util.SearchQuery
import com.ferlagod.misnotas.widget.NotesWidget
import com.ferlagod.misnotas.util.Tags
import com.ferlagod.misnotas.util.TextSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Filtro de navegación para la vista de notas.
 */
sealed interface NotesFilter {
    data object All : NotesFilter
    data object Pinned : NotesFilter
    data object Trash : NotesFilter
    data class Folder(val folderId: String, val folderName: String) : NotesFilter
    /** Notas con la etiqueta [tag] (o una subetiqueta suya), ver [Tags]. */
    data class Tag(val tag: String) : NotesFilter
}

/** Etiqueta con el número de notas que la usan. */
data class TagCount(val tag: String, val key: String, val count: Int)

/** Avisos puntuales para la interfaz (snackbars). */
sealed interface UiEvent {
    /** Se movieron notas a la papelera: la interfaz ofrece "Deshacer". */
    data class NotesTrashed(val ids: List<String>) : UiEvent
    data class Message(val text: String) : UiEvent
}

/** Navegación pedida desde fuera de la interfaz (accesos directos, compartir desde otra app). */
sealed interface PendingNavigation {
    data object NewNote : PendingNavigation
    data class OpenNote(val noteId: String) : PendingNavigation
}

/** Estado del inicio de sesión de Nextcloud en el navegador. */
sealed interface LoginFlowState {
    data object Idle : LoginFlowState
    data object Starting : LoginFlowState
    data class Waiting(val loginUrl: String) : LoginFlowState
    data class Done(val serverUrl: String, val username: String, val appPassword: String) : LoginFlowState
    data class Error(val message: String) : LoginFlowState
}

/** Estado de "Probar conexión". */
sealed interface DiagnosticsState {
    data object Idle : DiagnosticsState
    data object Running : DiagnosticsState
    data class Done(val rootUrl: String, val report: ConnectionDiagnostics.Report) : DiagnosticsState
}

/** Intervalo mínimo entre dos versiones guardadas mientras se edita. */
private const val REVISION_INTERVAL_MS = 10 * 60_000L
/** Versiones que se conservan por nota y antigüedad máxima. */
private const val REVISIONS_PER_NOTE = 50
private const val REVISION_MAX_AGE_DAYS = 180L

/** Id fijo de la nota de bienvenida: igual en todos los dispositivos para que no se duplique. */
private const val WELCOME_NOTE_ID = "misnotas-welcome"

/**
 * ViewModel principal: sesión/cifrado, sincronización, ajustes, carpetas y notas.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val folderDao = database.folderDao()
    private val noteDao = database.noteDao()
    private val attachmentDao = database.attachmentDao()
    private val historyDao = database.noteHistoryDao()
    private val attachmentStore = AttachmentStore(application)
    private val backupManager = BackupManager(application)

    private val syncRepository = SyncRepository.getInstance(application)
    private val authPreferences = syncRepository.authPreferences
    private val appSettings = AppSettings.getInstance(application)

    val syncState: StateFlow<SyncState> = syncRepository.syncState
    val settings: StateFlow<AppSettingsState> = appSettings.state

    /** Serializa las escrituras de notas (p. ej. autoguardado seguido de "mover a papelera"). */
    private val noteWriteMutex = Mutex()

    /**
     * Última versión de cada nota que conoce el editor. Si al guardar la base de datos tiene otra
     * cosa, es que cambió mientras la nota estaba abierta (sincronización, o acciones de la lista
     * en el panel lateral de una pantalla ancha):
     * - Texto: en vez de pisar lo recibido se conserva como copia "(conflicto)".
     * - Carpeta, color y fijado: solo se aplican los que el usuario cambió en el editor.
     */
    private data class EditorBase(
        val title: String,
        val content: String,
        val folderId: String?,
        val color: String,
        val isPinned: Boolean
    )

    private val editorBases = java.util.concurrent.ConcurrentHashMap<String, EditorBase>()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val _pendingNavigation = MutableStateFlow<PendingNavigation?>(null)
    val pendingNavigation: StateFlow<PendingNavigation?> = _pendingNavigation.asStateFlow()

    private val _isConfigured = MutableStateFlow(authPreferences.hasCredentials())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _isUnlocked = MutableStateFlow(
        if (authPreferences.hasCredentials()) authPreferences.isKeyUnlocked() else true
    )
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _currentFilter = MutableStateFlow<NotesFilter>(NotesFilter.All)
    val currentFilter: StateFlow<NotesFilter> = _currentFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** Hay una importación/exportación en curso. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Fecha de la copia previa a la conexión más reciente (null si no hay). */
    private val _safetyCopyTime = MutableStateFlow<Long?>(null)
    val safetyCopyTime: StateFlow<Long?> = _safetyCopyTime.asStateFlow()

    val allActiveNotes = noteDao.getAllNotesFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val trashNotes = noteDao.getTrashNotesFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val allFolders = folderDao.getAllFoldersFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val foldersMap: StateFlow<Map<String, FolderEntity>> = allFolders
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Texto normalizado (sin acentos) y etiquetas de una nota, para buscar y filtrar. */
    private class SearchEntry(val haystack: String, val tagKeys: Set<String>)

    private fun searchEntry(note: NoteEntity) = SearchEntry(
        TextSearch.normalize(note.title + "\n" + markdownPlainText(note.content)),
        Tags.extract(note.content).mapTo(HashSet()) { Tags.key(it) }
    )

    /** Índice de búsqueda, recalculado solo para las notas que cambian. */
    private val searchIndex = combine(allActiveNotes, trashNotes) { active, trash -> active + trash }
        .scan(emptyMap<String, Pair<NoteEntity, SearchEntry>>()) { previous, notes ->
            notes.associate { note ->
                val cached = previous[note.id]
                note.id to (if (cached != null && cached.first.content == note.content && cached.first.title == note.title) cached.copy(first = note)
                else note to searchEntry(note))
            }
        }
        .map { m -> m.mapValues { it.value.second } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Todas las etiquetas de las notas activas, con su recuento. */
    val allTags: StateFlow<List<TagCount>> = allActiveNotes
        .map { notes ->
            val counts = HashMap<String, Pair<String, Int>>()
            for (note in notes) for (tag in Tags.extract(note.content)) {
                val k = Tags.key(tag)
                counts[k] = (counts[k]?.first ?: tag) to ((counts[k]?.second ?: 0) + 1)
            }
            counts.map { (k, v) -> TagCount(v.first, k, v.second) }.sortedBy { it.key }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayedNotes: StateFlow<List<NoteEntity>> = combine(
        combine(allActiveNotes, trashNotes, ::Pair),
        _currentFilter,
        _searchQuery,
        settings.map { it.sortOrder }.distinctUntilChanged(),
        searchIndex
    ) { (activeNotes, trashList), filter, query, sortOrder, index ->
        fun entry(note: NoteEntity) = index[note.id] ?: searchEntry(note)
        val filtered = when (filter) {
            is NotesFilter.All -> activeNotes
            is NotesFilter.Pinned -> activeNotes.filter { it.isPinned }
            is NotesFilter.Trash -> trashList
            is NotesFilter.Folder -> activeNotes.filter { it.folderId == filter.folderId }
            is NotesFilter.Tag -> activeNotes.filter { Tags.matches(entry(it).tagKeys, filter.tag) }
        }
        val parsed = SearchQuery.parse(query)
        val matching = if (parsed.isEmpty) filtered else filtered.filter { note ->
            val e = entry(note)
            parsed.matches(e.haystack, e.tagKeys)
        }
        if (filter is NotesFilter.Trash) matching.sortedByDescending { it.updatedAt }
        else matching.sortedWith(compareByDescending<NoteEntity> { it.isPinned }.then(sortOrder.comparator()))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalActiveNotesCount = allActiveNotes.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pinnedNotesCount = allActiveNotes.map { notes -> notes.count { it.isPinned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val trashNotesCount = trashNotes.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private fun text(@StringRes id: Int, vararg args: Any): String =
        LanguageManager.localizedContext(getApplication()).getString(id, *args)

    private fun quantity(id: Int, count: Int): String =
        LanguageManager.localizedContext(getApplication()).resources.getQuantityString(id, count, count)

    init {
        viewModelScope.launch {
            syncRepository.migrateLegacyImages()
            purgeOldTrash()
            pruneHistory()
            Reminders.rescheduleAll(application)
        }
        // El widget refleja los cambios de las notas (también los que trae la sincronización).
        viewModelScope.launch {
            allActiveNotes.drop(1).collect { NotesWidget.requestUpdate(application) }
        }
        if (!settings.value.welcomeSeeded) viewModelScope.launch { seedWelcomeNote() }
        viewModelScope.launch {
            allFolders.collect { folders ->
                val current = _currentFilter.value
                if (current is NotesFilter.Folder) {
                    val match = folders.firstOrNull { it.id == current.folderId }
                    if (match == null) {
                        val exists = folderDao.getFolderById(current.folderId)?.let { !it.isDeleted } == true
                        if (!exists) _currentFilter.value = NotesFilter.All
                    } else if (match.name != current.folderName) {
                        _currentFilter.value = NotesFilter.Folder(match.id, match.name)
                    }
                }
            }
        }
        refreshSafetyCopy()
        if (settings.value.autoBackup && settings.value.autoBackupFolder != null) {
            AutoBackupWorker.schedule(application)
        }
        if (_isConfigured.value && _isUnlocked.value) {
            SyncWorker.schedule(application)
            triggerSync()
        }
    }

    // ==========================================
    // Ajustes
    // ==========================================

    fun dismissHint(key: String) = appSettings.dismissHint(key)

    /**
     * Primer arranque: crea una nota fijada que enseña la app usándola (formato, tareas,
     * gestos, copias y cifrado). Solo si no hay nada guardado ni cuenta configurada, para no
     * aparecer a quien actualiza la app.
     *
     * Usa un id fijo y se guarda como SINCRONIZADA y sin ETag: si el servidor ya tiene la nota
     * de otro dispositivo, gana la del servidor (sin conflicto ni duplicado); si no la tiene,
     * la sincronización la marca para subir.
     */
    private suspend fun seedWelcomeNote() {
        noteWriteMutex.withLock {
            if (!_isConfigured.value && noteDao.getAll().isEmpty() && folderDao.getAll().isEmpty()) {
                noteDao.upsertNote(
                    NoteEntity(
                        id = WELCOME_NOTE_ID,
                        title = text(R.string.welcome_note_title),
                        content = text(R.string.welcome_note_body, AppSettings.TRASH_RETENTION_DAYS).trimEnd(),
                        isPinned = true,
                        syncStatus = SyncStatus.SYNCED,
                        remoteEtag = null
                    )
                )
            }
        }
        appSettings.setWelcomeSeeded()
    }

    fun toggleLayoutMode() = appSettings.setGridLayout(!settings.value.gridLayout)
    fun setSortOrder(order: SortOrder) = appSettings.setSortOrder(order)
    fun setThemeMode(mode: ThemeMode) = appSettings.setThemeMode(mode)
    fun setAmoled(enabled: Boolean) = appSettings.setAmoled(enabled)
    fun setAppLock(enabled: Boolean) {
        appSettings.setAppLock(enabled)
        // El widget oculta o vuelve a enseñar las notas.
        NotesWidget.requestUpdate(getApplication())
    }

    fun setAutoPurgeTrash(enabled: Boolean) {
        appSettings.setAutoPurgeTrash(enabled)
        if (enabled) viewModelScope.launch { purgeOldTrash() }
    }

    // ==========================================
    // Historial de versiones
    // ==========================================

    // ==========================================
    // Recordatorios
    // ==========================================

    /** Recordatorio de cada nota (id -> instante). */
    val reminders: StateFlow<Map<String, Long>> = database.reminderDao().allFlow()
        .map { list -> list.associate { it.noteId to it.triggerAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setReminder(noteId: String, triggerAt: Long?) {
        viewModelScope.launch {
            if (triggerAt == null) Reminders.clear(getApplication(), noteId)
            else Reminders.set(getApplication(), noteId, triggerAt)
        }
    }

    fun observeRevisions(noteId: String): Flow<List<NoteRevisionEntity>> = historyDao.revisionsFlow(noteId)

    /**
     * Guarda la versión anterior de la nota antes de un cambio de texto. Como mucho una cada
     * [REVISION_INTERVAL_MS] mientras se escribe, para no llenar el historial con cada letra.
     */
    private suspend fun snapshotForEdit(existing: NoteEntity) {
        if (existing.title.isBlank() && existing.content.isBlank()) return
        val last = historyDao.latestRevision(existing.id)
        if (last != null && System.currentTimeMillis() - last.createdAt < REVISION_INTERVAL_MS) return
        if (last != null && last.title == existing.title && last.content == existing.content) return
        historyDao.insertRevision(NoteRevisionEntity(noteId = existing.id, title = existing.title, content = existing.content))
    }

    /**
     * Antes de restaurar una versión desde el editor: guarda la versión actual en el historial
     * (para poder deshacer) y avisa con la versión elegida. El editor aplica el texto y lo guarda
     * como cualquier otra edición.
     */
    fun prepareRestore(noteId: String, revisionId: Long, onReady: (NoteRevisionEntity) -> Unit) {
        viewModelScope.launch {
            val revision = noteWriteMutex.withLock {
                val revision = historyDao.getRevision(revisionId) ?: return@withLock null
                val current = noteDao.getNoteById(noteId)
                if (current != null && (current.title != revision.title || current.content != revision.content) &&
                    (current.title.isNotBlank() || current.content.isNotBlank())
                ) {
                    historyDao.insertRevision(
                        NoteRevisionEntity(noteId = noteId, title = current.title, content = current.content, reason = NoteRevisionEntity.Reason.BEFORE_RESTORE.key)
                    )
                }
                revision
            } ?: return@launch
            onReady(revision)
        }
    }

    fun deleteRevision(revisionId: Long) {
        viewModelScope.launch { historyDao.deleteRevision(revisionId) }
    }

    private suspend fun pruneHistory() {
        historyDao.deleteOrphanRevisions()
        historyDao.prune(REVISIONS_PER_NOTE, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(REVISION_MAX_AGE_DAYS))
    }

    /** Vacía de la papelera las notas que llevan más de 30 días en ella (si está activado). */
    private suspend fun purgeOldTrash() {
        if (!settings.value.autoPurgeTrash) return
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(AppSettings.TRASH_RETENTION_DAYS.toLong())
        val ids = noteDao.getTrashIdsOlderThan(before)
        if (ids.isEmpty()) return
        noteWriteMutex.withLock {
            database.withTransaction { ids.forEach { purgeLocked(it) } }
        }
        triggerSync()
    }

    // ==========================================
    // Cuenta y cifrado
    // ==========================================

    /**
     * Conecta la cuenta. Una dirección WebDAV de la raíz personal de Nextcloud se convierte en la
     * carpeta de la app (ver [ServerUrl]); si es la misma que ya está guardada se respeta, porque
     * cambiarla movería la bóveda de sitio.
     */
    fun setupAndUnlock(
        kind: ServerKind,
        serverUrl: String,
        username: String,
        appPassword: String,
        masterPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (serverUrl.isBlank() || username.isBlank() || appPassword.isBlank() || masterPassword.isBlank()) {
            onError(text(R.string.err_all_fields))
            return
        }
        val url = resolveServerUrl(kind, serverUrl)
        if (!ServerUrl.isAllowed(url)) {
            onError(text(R.string.err_https_required))
            return
        }
        viewModelScope.launch {
            val credentials = NextcloudCredentials(url, username.trim(), appPassword.trim(), kind)
            syncRepository.connectAccount(credentials, masterPassword)
                .onSuccess {
                    refreshSafetyCopy()
                    _isConfigured.value = true
                    _isUnlocked.value = true
                    SyncWorker.schedule(getApplication())
                    triggerSync()
                    onSuccess()
                }
                .onFailure { onError(it.message ?: text(R.string.err_connect_failed)) }
        }
    }

    private fun resolveServerUrl(kind: ServerKind, serverUrl: String): String {
        val saved = authPreferences.getCredentials()
        if (saved != null && saved.kind == kind && serverUrl.trim().trimEnd('/') == saved.serverUrl) return saved.serverUrl
        return if (kind == ServerKind.WEBDAV) ServerUrl.normalizeWebDav(serverUrl) else ServerUrl.normalize(serverUrl)
    }

    // --- Inicio de sesión de Nextcloud en el navegador (Login Flow v2) ---

    private val _loginFlow = MutableStateFlow<LoginFlowState>(LoginFlowState.Idle)
    val loginFlow: StateFlow<LoginFlowState> = _loginFlow.asStateFlow()
    private var loginFlowJob: Job? = null

    /**
     * Pide al servidor una sesión de inicio, abre el navegador con [openBrowser] y espera a que
     * el usuario autorice la app. El resultado (usuario y contraseña de aplicación) llega en [loginFlow].
     */
    fun startNextcloudLogin(serverUrl: String, openBrowser: (String) -> Unit) {
        loginFlowJob?.cancel()
        val typed = ServerUrl.normalize(serverUrl)
        if (ServerUrl.host(typed) == null) {
            _loginFlow.value = LoginFlowState.Error(text(R.string.err_all_fields))
            return
        }
        if (!ServerUrl.isAllowed(typed)) {
            _loginFlow.value = LoginFlowState.Error(text(R.string.err_https_required))
            return
        }
        loginFlowJob = viewModelScope.launch {
            _loginFlow.value = LoginFlowState.Starting
            try {
                val flow = LoginFlow(HttpClientProvider.get(getApplication()))
                val session = flow.start(typed)
                _loginFlow.value = LoginFlowState.Waiting(session.loginUrl)
                openBrowser(session.loginUrl)
                val result = flow.poll(session)
                // Si el usuario eligió una subcarpeta WebDAV concreta se respeta; si no, se usa
                // la dirección que devuelve el servidor (puede diferir de la escrita).
                val server = if (typed.contains("/remote.php/dav/files/", ignoreCase = true)) typed else ServerUrl.normalize(result.server)
                _loginFlow.value = LoginFlowState.Done(server, result.loginName, result.appPassword)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LoginFlow.NotSupportedException) {
                _loginFlow.value = LoginFlowState.Error(text(R.string.login_flow_not_supported))
            } catch (_: java.util.concurrent.TimeoutException) {
                _loginFlow.value = LoginFlowState.Error(text(R.string.login_flow_expired))
            } catch (e: Exception) {
                _loginFlow.value = LoginFlowState.Error(syncRepository.friendlyMessage(e))
            }
        }
    }

    fun cancelNextcloudLogin() {
        loginFlowJob?.cancel()
        loginFlowJob = null
        _loginFlow.value = LoginFlowState.Idle
    }

    // --- Prueba de conexión y certificados ---

    private val _diagnostics = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()
    private var diagnosticsJob: Job? = null

    fun testConnection(kind: ServerKind, serverUrl: String, username: String, appPassword: String) {
        diagnosticsJob?.cancel()
        val url = resolveServerUrl(kind, serverUrl)
        val root = authPreferences.buildWebDavRootUrl(NextcloudCredentials(url, username.trim(), appPassword.trim(), kind))
        diagnosticsJob = viewModelScope.launch {
            _diagnostics.value = DiagnosticsState.Running
            val report = ConnectionDiagnostics(getApplication()).run(root, username.trim(), appPassword.trim())
            _diagnostics.value = DiagnosticsState.Done(root, report)
        }
    }

    fun dismissDiagnostics() {
        diagnosticsJob?.cancel()
        _diagnostics.value = DiagnosticsState.Idle
    }

    private val trustedCertificates = TrustedCertificates(application)

    private val _trustedCerts = MutableStateFlow(trustedCertificates.infos())
    val trustedCerts: StateFlow<List<TrustedCertificates.Info>> = _trustedCerts.asStateFlow()

    fun trustCertificate(cert: java.security.cert.X509Certificate) {
        trustedCertificates.add(cert)
        _trustedCerts.value = trustedCertificates.infos()
    }

    /** Importa un certificado (PEM o DER) elegido por el usuario: el de su CA o el del servidor. */
    fun importCertificate(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw java.io.IOException("empty")
                TrustedCertificates.parse(bytes).forEach { trustedCertificates.add(it) }
                _trustedCerts.value = trustedCertificates.infos()
                _events.send(UiEvent.Message(text(R.string.cert_imported)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(UiEvent.Message(text(R.string.cert_import_failed)))
            }
        }
    }

    fun removeCertificate(sha256: String) {
        trustedCertificates.remove(sha256)
        _trustedCerts.value = trustedCertificates.infos()
    }

    fun unlockWithPassword(
        masterPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val key = syncRepository.vault.unlockLocal(masterPassword)
                authPreferences.saveDerivedKey(key)
                _isUnlocked.value = true
                NotesWidget.requestUpdate(getApplication())
                SyncWorker.schedule(getApplication())
                triggerSync()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(syncRepository.friendlyMessage(e))
            }
        }
    }

    fun lock() {
        if (!_isConfigured.value) return
        authPreferences.clearDerivedKey()
        _isUnlocked.value = false
        NotesWidget.requestUpdate(getApplication())
    }

    fun disconnectNextcloud() {
        SyncWorker.cancel(getApplication())
        authPreferences.clearCredentials()
        authPreferences.clearDerivedKey()
        syncRepository.resetSyncState()
        _isConfigured.value = false
        _isUnlocked.value = true
        viewModelScope.launch { clearPendingPurgesLocked() }
    }

    fun logout() {
        SyncWorker.cancel(getApplication())
        authPreferences.clearAll()
        syncRepository.resetSyncState()
        _isConfigured.value = false
        _isUnlocked.value = true
        viewModelScope.launch { clearPendingPurgesLocked() }
    }

    private suspend fun clearPendingPurgesLocked() {
        noteWriteMutex.withLock {
            database.withTransaction {
                noteDao.deletePendingPurge()
                folderDao.deletePendingPurge()
                database.remoteTombstoneDao().clear()
            }
        }
    }

    /** Lee la nota después de cualquier guardado pendiente (p. ej. al pasar del panel a pantalla completa). */
    suspend fun getNoteById(id: String): NoteEntity? = noteWriteMutex.withLock { noteDao.getNoteById(id) }

    /** Nota en vivo, para que el editor muestre cambios de otros dispositivos si no se ha tocado. */
    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.getNoteByIdFlow(id)

    fun getCredentials(): NextcloudCredentials? = authPreferences.getCredentials()

    fun getLastSync(): Long? = authPreferences.getLastSync()

    // ==========================================
    // Sincronización
    // ==========================================

    fun triggerSync() {
        if (!_isConfigured.value || !_isUnlocked.value) return
        viewModelScope.launch { syncRepository.synchronize() }
    }

    // ==========================================
    // Navegación y búsqueda
    // ==========================================

    fun setFilter(filter: NotesFilter) {
        _currentFilter.value = filter
        _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun requestNavigation(navigation: PendingNavigation) {
        _pendingNavigation.value = navigation
    }

    fun consumeNavigation() {
        _pendingNavigation.value = null
    }

    // ==========================================
    // Carpetas
    // ==========================================

    fun createFolder(name: String, color: NoteColor, parentId: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val siblingConflict = folderDao.getAll().any {
                !it.isDeleted && it.parentId == parentId && it.name.equals(trimmed, ignoreCase = true)
            }
            if (siblingConflict) return@launch
            folderDao.upsertFolder(
                FolderEntity(
                    id = UUID.randomUUID().toString(),
                    parentId = parentId,
                    name = trimmed,
                    color = color.key,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.PENDING_UPLOAD
                )
            )
            triggerSync()
        }
    }

    fun updateFolder(id: String, name: String, color: NoteColor, parentId: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // Evita ciclos: una carpeta no puede colgar de sí misma ni de una de sus subcarpetas.
            val folders = folderDao.getAll().filter { !it.isDeleted }
            val safeParent = parentId?.takeUnless { FolderTree.wouldCreateCycle(folders, id, it) }
            val siblingConflict = folders.any {
                it.id != id && it.parentId == safeParent && it.name.equals(trimmed, ignoreCase = true)
            }
            if (siblingConflict) return@launch
            folderDao.updateFolderDetails(id, trimmed, color.key, safeParent)
            triggerSync()
        }
    }

    /**
     * Elimina una carpeta: sus notas pasan a "Todas las notas" y sus subcarpetas suben un nivel.
     */
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            database.withTransaction {
                val folder = folderDao.getFolderById(folderId) ?: return@withTransaction
                noteDao.detachFromFolder(folderId)
                folderDao.reparentChildren(folderId, folder.parentId)
                if (_isConfigured.value) folderDao.markAsDeleted(folderId)
                else folderDao.deletePermanently(folderId)
            }
            if (_currentFilter.value.let { it is NotesFilter.Folder && it.folderId == folderId }) {
                _currentFilter.value = NotesFilter.All
            }
            triggerSync()
        }
    }

    // ==========================================
    // Notas
    // ==========================================

    /**
     * Guarda una nota. No hace nada si no hay cambios reales (así abrir y cerrar una nota no
     * altera su fecha ni provoca subidas). Conserva el estado remoto y de papelera existentes.
     *
     * @param sync si es false solo guarda en local (autoguardado mientras se escribe).
     * @param closing true al salir del editor: una nota que se ha quedado vacía se descarta.
     */
    fun saveNote(
        id: String,
        title: String,
        content: String,
        folderId: String? = null,
        color: NoteColor = NoteColor.DEFAULT,
        isPinned: Boolean = false,
        sync: Boolean = true,
        closing: Boolean = false
    ) {
        viewModelScope.launch {
            noteWriteMutex.withLock { saveNoteLocked(id, title, content, folderId, color, isPinned, closing) }
            if (sync) triggerSync()
        }
    }

    /** El editor ha cargado (o adoptado) esta versión de la nota y muestra estos datos. */
    fun registerEditorBase(id: String, title: String, content: String, folderId: String?, color: NoteColor, isPinned: Boolean) {
        editorBases[id] = EditorBase(title, content, folderId, color.key, isPinned)
    }

    private suspend fun saveNoteLocked(
        id: String,
        title: String,
        content: String,
        folderId: String?,
        color: NoteColor,
        isPinned: Boolean,
        closing: Boolean
    ) = database.withTransaction {
        saveNoteInTransaction(id, title, content, folderId, color, isPinned, closing)
    }

    private suspend fun saveNoteInTransaction(
        id: String,
        title: String,
        content: String,
        folderId: String?,
        color: NoteColor,
        isPinned: Boolean,
        closing: Boolean
    ) {
        val existing = noteDao.getNoteById(id)
        var cleanTitle = title.trim()
        var cleanContent = content.trimEnd()

        val base = editorBases[id]
        // Carpeta, color y fijado: si el editor no los ha cambiado, se respeta lo que haya en la
        // base de datos (quizá lo cambió otro dispositivo o la lista mientras la nota estaba abierta).
        val finalFolderId = if (existing != null && base != null && folderId == base.folderId) existing.folderId else folderId
        val finalColor = if (existing != null && base != null && color.key == base.color) existing.color else color.key
        val finalPinned = if (existing != null && base != null && isPinned == base.isPinned) existing.isPinned else isPinned
        val editorMeta = { t: String, c: String -> EditorBase(t, c, folderId, color.key, isPinned) }

        if (existing != null && base != null && (existing.title != base.title || existing.content != base.content)) {
            if (cleanTitle == base.title.trim() && cleanContent == base.content.trimEnd()) {
                // El usuario no ha tocado el texto: gana la versión recibida (solo se aplican
                // los cambios de color, carpeta o fijado que haya hecho en el editor).
                cleanTitle = existing.title
                cleanContent = existing.content
            } else if (existing.title != cleanTitle || existing.content != cleanContent) {
                // Ambos lados cambiaron el texto: la versión recibida se guarda como copia.
                noteDao.upsertNote(
                    SyncMerger.conflictCopy(
                        existing,
                        UUID.randomUUID().toString(),
                        text(R.string.conflict_suffix),
                        text(R.string.untitled)
                    )
                )
            }
            editorBases[id] = editorMeta(cleanTitle, cleanContent)
        }

        if (cleanTitle.isEmpty() && cleanContent.isBlank()) {
            // Como en Keep o Apple Notas: una nota vacía no se guarda. Si antes tenía contenido
            // (el autoguardado no guarda vacíos, así que sigue en la base de datos) va a la
            // papelera con opción de deshacer, por si se borró todo sin querer.
            if (existing != null && closing && !existing.isDeleted) {
                if (existing.title.isBlank() && existing.content.isBlank()) {
                    purgeLocked(id)
                } else {
                    noteDao.moveToTrash(id)
                    _events.trySend(UiEvent.NotesTrashed(listOf(id)))
                }
            }
            return
        }

        if (existing != null &&
            existing.title == cleanTitle &&
            existing.content == cleanContent &&
            existing.folderId == finalFolderId &&
            existing.color == finalColor &&
            existing.isPinned == finalPinned
        ) {
            if (base != null) editorBases[id] = editorMeta(base.title, base.content)
            return
        }

        if (existing != null && (existing.title != cleanTitle || existing.content != cleanContent)) {
            snapshotForEdit(existing)
        }

        val entity = (existing ?: NoteEntity(id = id, title = "", content = "")).copy(
            folderId = finalFolderId,
            title = cleanTitle,
            content = cleanContent,
            isPinned = finalPinned,
            color = finalColor,
            updatedAt = System.currentTimeMillis(),
            syncStatus = if (existing?.syncStatus == SyncStatus.PENDING_DELETE) SyncStatus.PENDING_DELETE else SyncStatus.PENDING_UPLOAD
        )
        noteDao.upsertNote(entity)
        editorBases[id] = editorMeta(cleanTitle, cleanContent)
    }

    /** Borrado definitivo (con lápida en el servidor si hay cuenta). Requiere el cerrojo de escritura. */
    private suspend fun purgeLocked(id: String) {
        if (_isConfigured.value) noteDao.markForPurge(id) else noteDao.deletePermanently(id)
    }

    /** Crea una nota nueva (p. ej. con texto compartido desde otra app) y devuelve su id. */
    suspend fun createNote(title: String, content: String, folderId: String? = null): String {
        val note = NoteEntity(
            title = title.trim(),
            content = content.trimEnd(),
            folderId = folderId,
            syncStatus = SyncStatus.PENDING_UPLOAD
        )
        noteWriteMutex.withLock { noteDao.upsertNote(note) }
        triggerSync()
        return note.id
    }

    /** Crea una nota a partir de contenido compartido y la abre. */
    fun createNoteFromShare(subject: String?, sharedText: String?, imageUris: List<Uri>) {
        viewModelScope.launch {
            val images = imageUris.mapNotNull { uri ->
                try {
                    "![${text(R.string.image_alt)}](${addImageAttachment(uri)})"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            // Algunas apps comparten texto con saltos de línea de Windows ("\r\n"): el editor y las
            // listas de tareas trabajan con "\n".
            val cleanText = sharedText?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim()
            val body = listOfNotNull(cleanText?.takeIf { it.isNotEmpty() }, images.joinToString("\n").ifEmpty { null })
                .joinToString("\n\n")
            if (body.isBlank() && subject.isNullOrBlank()) {
                if (imageUris.isNotEmpty()) _events.trySend(UiEvent.Message(text(R.string.image_add_failed)))
                return@launch
            }
            val id = createNote(subject.orEmpty(), body)
            requestNavigation(PendingNavigation.OpenNote(id))
        }
    }

    /** Duplica una nota y devuelve el id de la copia. */
    suspend fun duplicateNote(noteId: String): String? {
        // Dentro del cerrojo: así se copia también el último autoguardado pendiente.
        val copy = noteWriteMutex.withLock {
            val original = noteDao.getNoteById(noteId) ?: return null
            original.copy(
                id = UUID.randomUUID().toString(),
                title = (original.title.ifBlank { text(R.string.untitled) } + " " + text(R.string.copy_suffix)).trim(),
                isPinned = false,
                isDeleted = false,
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING_UPLOAD,
                remoteEtag = null
            ).also { noteDao.upsertNote(it) }
        }
        triggerSync()
        return copy.id
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch {
            noteWriteMutex.withLock {
                noteDao.setPinned(note.id, !note.isPinned)
            }
            triggerSync()
        }
    }

    /** Mueve la nota a la papelera. */
    fun deleteNote(noteId: String) = moveNotesToTrash(listOf(noteId))

    /** Mueve varias notas a la papelera y ofrece deshacer. */
    fun moveNotesToTrash(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteWriteMutex.withLock {
                database.withTransaction { ids.forEach { noteDao.moveToTrash(it) } }
            }
            _events.trySend(UiEvent.NotesTrashed(ids.toList()))
            triggerSync()
        }
    }

    fun restoreNote(noteId: String) = restoreNotes(listOf(noteId))

    /**
     * La sincronización trajo la nota abierta en el editor como enviada a la papelera. Con
     * [keepEdits] el usuario estaba escribiendo: se restaura para que lo escrito no acabe en la
     * papelera sin que lo sepa.
     */
    fun onNoteTrashedElsewhere(noteId: String, keepEdits: Boolean) {
        if (keepEdits) {
            restoreNotes(listOf(noteId))
            _events.trySend(UiEvent.Message(text(R.string.note_trashed_elsewhere_restored)))
        } else {
            _events.trySend(UiEvent.Message(text(R.string.note_trashed_elsewhere)))
        }
    }

    fun restoreNotes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteWriteMutex.withLock {
                database.withTransaction { ids.forEach { noteDao.restoreFromTrash(it) } }
            }
            triggerSync()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            noteWriteMutex.withLock {
                if (_isConfigured.value) noteDao.markTrashForPurge() else noteDao.deleteTrashPermanently()
            }
            triggerSync()
        }
    }

    fun deleteNotePermanently(noteId: String) = deleteNotesPermanently(listOf(noteId))

    fun deleteNotesPermanently(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteWriteMutex.withLock {
                database.withTransaction { ids.forEach { purgeLocked(it) } }
            }
            triggerSync()
        }
    }

    fun setNotesPinned(ids: Collection<String>, pinned: Boolean) = bulkUpdate { noteDao.setPinnedMany(ids.toList(), pinned) }

    fun setNotesColor(ids: Collection<String>, color: NoteColor) = bulkUpdate { noteDao.setColor(ids.toList(), color.key) }

    fun moveNotes(ids: Collection<String>, folderId: String?) = bulkUpdate { noteDao.moveToFolder(ids.toList(), folderId) }

    private fun bulkUpdate(block: suspend () -> Unit) {
        viewModelScope.launch {
            noteWriteMutex.withLock { block() }
            triggerSync()
        }
    }

    /**
     * Comprime la imagen, la guarda como adjunto y devuelve la referencia Markdown
     * (`attachment://<id>`) para insertarla en la nota.
     */
    /** El fichero supera [AttachmentStore.MAX_FILE_BYTES]. */
    class AttachmentTooLargeException : Exception("Attachment too large")

    /**
     * Adjunta un fichero cualquiera (PDF, audio, documento…) tal cual, sin comprimir.
     * Devuelve el enlace Markdown que se inserta en la nota.
     */
    suspend fun addFileAttachment(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > AttachmentStore.MAX_FILE_BYTES) throw AttachmentTooLargeException()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > AttachmentStore.MAX_FILE_BYTES) throw AttachmentTooLargeException()
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw java.io.IOException("Cannot read $uri")
        val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: AttachmentStore.mimeForName(name)
            ?: AttachmentStore.detectMimeType(bytes, fallback = "application/octet-stream")
        val id = UUID.randomUUID().toString()
        attachmentStore.write(id, bytes)
        attachmentDao.upsert(AttachmentEntity(id = id, mimeType = mime, size = bytes.size.toLong()))
        AttachmentStore.fileLink(name, id)
    }

    suspend fun addImageAttachment(uri: Uri): String {
        val bytes = ImageCompressor.compressImage(getApplication(), uri)
        val id = UUID.randomUUID().toString()
        // La escritura del fichero no debe bloquear el hilo principal (se llama desde la UI).
        withContext(Dispatchers.IO) { attachmentStore.write(id, bytes) }
        attachmentDao.upsert(
            AttachmentEntity(id = id, mimeType = ImageCompressor.MIME_TYPE, size = bytes.size.toLong())
        )
        return AttachmentStore.reference(id)
    }

    // ==========================================
    // Copia de seguridad
    // ==========================================

    fun exportBackup(uri: Uri) = runBusy {
        val count = backupManager.exportZip(uri, text(R.string.untitled))
        _events.trySend(UiEvent.Message(quantity(R.plurals.export_done, count)))
    }

    /** Saca la última copia previa a la conexión a [uri], elegido por el usuario. */
    fun saveSafetyCopy(uri: Uri) = runBusy {
        backupManager.saveLatestSafetyCopy(uri)
        _events.trySend(UiEvent.Message(text(R.string.safety_copy_saved)))
    }

    private fun refreshSafetyCopy() {
        viewModelScope.launch(Dispatchers.IO) {
            _safetyCopyTime.value = backupManager.latestSafetyCopy()?.lastModified()
        }
    }

    fun importBackup(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runBusy {
            val result = backupManager.import(uris)
            val message = if (result.notes == 0) text(R.string.import_nothing) else quantity(R.plurals.import_done, result.notes)
            _events.trySend(UiEvent.Message(message))
            if (result.notes > 0) triggerSync()
        }
    }

    /** Activa o desactiva la copia automática diaria (requiere haber elegido carpeta). */
    fun setAutoBackup(enabled: Boolean) {
        val canEnable = enabled && settings.value.autoBackupFolder != null
        appSettings.setAutoBackup(canEnable)
        if (canEnable) AutoBackupWorker.schedule(getApplication(), replace = true)
        else AutoBackupWorker.cancel(getApplication())
    }

    /**
     * Guarda la carpeta elegida con ACTION_OPEN_DOCUMENT_TREE (con permiso persistente, para
     * poder escribir en segundo plano), activa la copia automática y hace la primera enseguida.
     */
    fun setAutoBackupFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            _events.trySend(UiEvent.Message(text(R.string.backup_failed, e.localizedMessage ?: e.javaClass.simpleName)))
            return
        }
        val previous = settings.value.autoBackupFolder
        if (previous != null && previous != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(previous.toUri(), flags) }
        }
        appSettings.setAutoBackupFolder(uri.toString())
        setAutoBackup(true)
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(UiEvent.Message(text(R.string.backup_failed, e.localizedMessage ?: e.javaClass.simpleName)))
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Comparador de notas para cada criterio de orden. */
fun SortOrder.comparator(): Comparator<NoteEntity> = when (this) {
    SortOrder.MODIFIED_DESC -> compareByDescending { it.updatedAt }
    SortOrder.MODIFIED_ASC -> compareBy { it.updatedAt }
    SortOrder.TITLE_ASC -> compareBy<NoteEntity, String>(String.CASE_INSENSITIVE_ORDER) {
        it.title.ifBlank { markdownPreview(it.content, maxLines = 1) }
    }.thenByDescending { it.updatedAt }
    SortOrder.TITLE_DESC -> compareByDescending<NoteEntity, String>(String.CASE_INSENSITIVE_ORDER) {
        it.title.ifBlank { markdownPreview(it.content, maxLines = 1) }
    }.thenByDescending { it.updatedAt }
}

class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
