package com.appdevforall.contractor.plugin.tracker

import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.editor.OnPauseEvent
import com.itsaky.androidide.eventbus.events.editor.OnResumeEvent
import com.itsaky.androidide.eventbus.events.project.ProjectInitializedEvent
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.BuildStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.nio.file.Path

class SessionTracker(
    private val pluginContext: PluginContext,
    private val projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository,
    private val settingsStore: SettingsStore
) : BuildStatusListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var activeProjectId: String? = null
    @Volatile private var activeProjectRoot: String? = null
    @Volatile private var activeSession: WorkSessionEntity? = null
    @Volatile private var lastHeartbeatAt: Long = 0L
    @Volatile private var lastPersistedHeartbeatAt: Long = 0L
    @Volatile private var pendingEditCount: Int = 0
    @Volatile private var pendingSaveCount: Int = 0

    private var idleJob: Job? = null
    private var registered = false

    private val _activeProjectIdState = MutableStateFlow<String?>(null)
    val activeProjectIdState: StateFlow<String?> = _activeProjectIdState.asStateFlow()

    fun start() {
        scope.launch {
            recoverOrphanedSessions()
            tryRegisterEventBus()
            evaluateActiveProject(System.currentTimeMillis())
            startIdleLoop()
        }
    }

    fun stop() {
        runCatching {
            if (registered) EventBus.getDefault().unregister(this)
        }
        registered = false
        idleJob?.cancel()
        idleJob = null
        scope.launch {
            endActiveSession(System.currentTimeMillis())
        }
        scope.cancel()
    }

    private fun tryRegisterEventBus() {
        if (registered) return
        try {
            EventBus.getDefault().register(this)
            registered = true
            pluginContext.logger.info("ContractorPlugin: SessionTracker registered with EventBus")
        } catch (e: Throwable) {
            pluginContext.logger.error("ContractorPlugin: failed to register EventBus", e)
        }

        runCatching {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            buildService?.addBuildStatusListener(this)
        }
    }

    private suspend fun recoverOrphanedSessions() {
        val orphaned = sessionRepository.getAllActive()
        if (orphaned.isEmpty()) return
        orphaned.forEach { s ->
            sessionRepository.closeSession(s.id, s.lastHeartbeatAt)
            pluginContext.logger.info("ContractorPlugin: closed orphaned session ${s.id} at heartbeat=${s.lastHeartbeatAt}")
        }
    }

    private suspend fun evaluateActiveProject(nowMs: Long) {
        val current = runCatching {
            pluginContext.services.get(IdeProjectService::class.java)?.getCurrentProject()
        }.getOrNull()
        val rootPath = current?.rootDir?.absolutePath
        if (rootPath == null) {
            switchActiveProject(null, null, nowMs)
            return
        }
        val tracked = projectRepository.getByRootPath(rootPath)
        if (tracked == null) {
            switchActiveProject(null, null, nowMs)
            return
        }
        switchActiveProject(tracked.id, rootPath, nowMs)
    }

    private suspend fun switchActiveProject(newId: String?, newRoot: String?, nowMs: Long) = mutex.withLock {
        if (newId == activeProjectId) {
            activeProjectRoot = newRoot
            return@withLock
        }
        endActiveSessionLocked(nowMs)
        activeProjectId = newId
        activeProjectRoot = newRoot
        _activeProjectIdState.value = newId
    }

    private suspend fun endActiveSession(nowMs: Long) = mutex.withLock {
        endActiveSessionLocked(nowMs)
    }

    private suspend fun endActiveSessionLocked(nowMs: Long) {
        val session = activeSession ?: return
        val effectiveEnd = if (lastHeartbeatAt > session.startTime) lastHeartbeatAt else nowMs
        sessionRepository.heartbeat(session.id, effectiveEnd, pendingEditCount, pendingSaveCount)
        sessionRepository.closeSession(session.id, effectiveEnd)
        pluginContext.logger.info("ContractorPlugin: closed session ${session.id}, durationMs=${effectiveEnd - session.startTime}")
        activeSession = null
        pendingEditCount = 0
        pendingSaveCount = 0
        lastPersistedHeartbeatAt = 0L
    }

    private suspend fun ensureActiveSession(nowMs: Long): WorkSessionEntity? = mutex.withLock {
        val pid = activeProjectId ?: return@withLock null
        activeSession?.let { return@withLock it }
        val started = sessionRepository.startSession(pid, nowMs)
        activeSession = started
        lastHeartbeatAt = nowMs
        lastPersistedHeartbeatAt = nowMs
        pendingEditCount = 0
        pendingSaveCount = 0
        pluginContext.logger.info("ContractorPlugin: started session ${started.id} for project $pid")
        started
    }

    private fun pathBelongsToActiveProject(rawPath: String?): Boolean {
        val rootPath = activeProjectRoot ?: return false
        val target = rawPath ?: return false
        return target.startsWith(rootPath)
    }

    private fun pathString(p: Path?): String? = p?.toAbsolutePath()?.toString()

    private fun handleHeartbeat(rawPath: String?, nowMs: Long, isSave: Boolean) {
        if (!pathBelongsToActiveProject(rawPath)) return
        scope.launch {
            val session = ensureActiveSession(nowMs) ?: return@launch
            mutex.withLock {
                lastHeartbeatAt = nowMs
                if (isSave) pendingSaveCount += 1 else pendingEditCount += 1
                if (nowMs - lastPersistedHeartbeatAt >= HEARTBEAT_PERSIST_INTERVAL_MS) {
                    sessionRepository.heartbeat(session.id, nowMs, pendingEditCount, pendingSaveCount)
                    pendingEditCount = 0
                    pendingSaveCount = 0
                    lastPersistedHeartbeatAt = nowMs
                }
            }
        }
    }

    private fun startIdleLoop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_TICK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val thresholdMs = settingsStore.idleThresholdMinutes * 60_000L
                val session = activeSession ?: continue
                if (now - lastHeartbeatAt >= thresholdMs) {
                    pluginContext.logger.info("ContractorPlugin: idle threshold hit for session ${session.id}")
                    endActiveSession(now)
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDocumentChange(event: DocumentChangeEvent) {
        handleHeartbeat(pathString(event.changedFile), System.currentTimeMillis(), isSave = false)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDocumentSave(event: DocumentSaveEvent) {
        handleHeartbeat(pathString(event.savedFile), System.currentTimeMillis(), isSave = true)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDocumentOpen(event: DocumentOpenEvent) {
        handleHeartbeat(pathString(event.openedFile), System.currentTimeMillis(), isSave = false)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDocumentClose(event: DocumentCloseEvent) {
        handleHeartbeat(pathString(event.closedFile), System.currentTimeMillis(), isSave = false)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onProjectInitialized(event: ProjectInitializedEvent) {
        scope.launch { evaluateActiveProject(System.currentTimeMillis()) }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onResume(event: OnResumeEvent) {
        scope.launch { evaluateActiveProject(System.currentTimeMillis()) }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPause(event: OnPauseEvent) {
        scope.launch {
            mutex.withLock {
                val s = activeSession ?: return@withLock
                sessionRepository.heartbeat(s.id, lastHeartbeatAt, pendingEditCount, pendingSaveCount)
                pendingEditCount = 0
                pendingSaveCount = 0
                lastPersistedHeartbeatAt = lastHeartbeatAt
            }
        }
    }

    override fun onBuildStarted() {
        if (activeProjectId == null) return
        scope.launch {
            ensureActiveSession(System.currentTimeMillis())
            mutex.withLock {
                lastHeartbeatAt = System.currentTimeMillis()
            }
        }
    }

    override fun onBuildFinished() {
        if (activeProjectId == null) return
        scope.launch {
            mutex.withLock {
                lastHeartbeatAt = System.currentTimeMillis()
            }
        }
    }

    override fun onBuildFailed(error: String?) {
        onBuildFinished()
    }

    fun onProjectRegistrationChanged() {
        scope.launch { evaluateActiveProject(System.currentTimeMillis()) }
    }

    /**
     * Called when the UI manually deletes a session row. If it was the in-memory active
     * session, drop our reference and immediately open a fresh session on the same project
     * so the project's "Tracking" pill returns without waiting for the user's next keystroke.
     * The new row replaces the deleted one as the active session; the idle timer will close
     * it if no actual editor activity follows.
     */
    fun onSessionDeleted(sessionId: String) {
        scope.launch {
            var shouldResume = false
            mutex.withLock {
                if (activeSession?.id != sessionId) return@withLock
                activeSession = null
                pendingEditCount = 0
                pendingSaveCount = 0
                lastPersistedHeartbeatAt = 0L
                shouldResume = activeProjectId != null
            }
            if (shouldResume) {
                ensureActiveSession(System.currentTimeMillis())
            }
        }
    }

    companion object {
        private const val IDLE_TICK_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_PERSIST_INTERVAL_MS = 60_000L
    }
}
