package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.PairWebSocketClient
import com.appdevforall.pair.plugin.data.PairWebSocketServer
import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.appdevforall.pair.plugin.data.SessionRole
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.util.NetUtil
import com.appdevforall.pair.plugin.util.PairLog
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.FileChangeListener
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.java_websocket.WebSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val PIN_LENGTH = 4
private const val PIN_BOUND = 10_000
private const val CURSOR_POLL_INTERVAL_MS = 150L

class EditBroker(
    private val editorService: IdeEditorService,
    private val fileService: IdeFileService,
    private val projectService: IdeProjectService?,
    private val logger: PluginLogger,
    private var displayName: String,
    private var showPeerCursors: Boolean = true,
) {

    fun setDisplayName(name: String) {
        displayName = name
        _state.value = _state.value.copy(localDisplayName = name)
    }

    fun setShowPeerCursors(enabled: Boolean) {
        showPeerCursors = enabled
        PairLog.d("[MARKERS] show-peer-cursors toggled = $enabled")
        if (!enabled) markers.clearAll()
        _state.value = _state.value.copy(showPeerCursors = enabled)
    }

    private val localPeerId: String = UUID.randomUUID().toString()
    private val localColorIndex: Int = (0 until 5).random()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val suppression = LoopbackSuppression()
    private val peerRegistry = PeerRegistry()
    private val pathMapper = PathMapper(projectService)
    private val applier = EditApplier(
        editorService = editorService,
        scope = scope,
        suppression = suppression,
        pathMapper = pathMapper,
        logger = logger,
        onOutOfSync = { wireFile ->
            _state.value = _state.value.copy(outOfSync = true)
            if (wireFile != null) requestResync(wireFile)
        },
    )
    private val observer = EditObserver(localPeerId, editorService, suppression, pathMapper, OutboundDispatcher())
    private val fsSuppression = LoopbackSuppression()
    private val fsApplier = FileSystemApplier(
        fileService = fileService,
        scope = scope,
        suppression = fsSuppression,
        pathMapper = pathMapper,
        logger = logger,
        onOutOfSync = { _state.value = _state.value.copy(outOfSync = true) },
    )
    private val fsObserver = FileSystemObserver(localPeerId, pathMapper, fsSuppression, OutboundDispatcher(), logger)
    private val manifestBuilder = ProjectManifestBuilder(projectService, logger)
    private val syncCoordinator = ProjectSyncCoordinator(
        localPeerId = localPeerId,
        manifestBuilder = manifestBuilder,
        fileService = fileService,
        pathMapper = pathMapper,
        suppression = fsSuppression,
        scope = scope,
        logger = logger,
        onProgress = { received, total ->
            _state.value = _state.value.copy(transferReceived = received, transferTotal = total)
        },
        onComplete = { pulledRoot ->
            _state.value = _state.value.copy(outOfSync = false)
            val currentRoot = runCatching { projectService?.getCurrentProject()?.rootDir?.absolutePath }.getOrNull()
            if (pulledRoot != null && pulledRoot.absolutePath != currentRoot) {
                pendingProjectRoot.set(pulledRoot)
                _state.value = _state.value.copy(pendingProjectPath = pulledRoot.absolutePath)
                PairLog.d("[PULL] complete → awaiting confirmation to open ${pulledRoot.absolutePath}")
            } else {
                PairLog.d("[PULL] complete → already on ${pulledRoot?.absolutePath ?: "(none)"}; files re-synced, no reopen needed")
            }
        },
    )
    private val markers = RemoteMarkerController(editorService, pathMapper)

    // The host reports the active editor file (null when a non-file tab — e.g. the Pair tab — is
    // shown). Clear remote peer cursors whenever the local user leaves the editor so stale markers
    // don't linger over the plugin UI.
    private val fileChangeListener = FileChangeListener { file ->
        if (file == null) {
            PairLog.d("[MARKERS] active file → null (left editor); clearing peer cursors")
            markers.clearAll()
        } else {
            PairLog.d("[MARKERS] active file → ${file.name}")
        }
    }
    private val connToPeer: ConcurrentHashMap<WebSocket, String> = ConcurrentHashMap()

    private val serverRef: AtomicReference<PairWebSocketServer?> = AtomicReference(null)
    private val clientRef: AtomicReference<PairWebSocketClient?> = AtomicReference(null)
    private val pendingRemote: AtomicReference<String?> = AtomicReference(null)
    private val pendingLocalIp: AtomicReference<String?> = AtomicReference(null)
    private val pendingProjectRoot: AtomicReference<java.io.File?> = AtomicReference(null)
    private val resyncPending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cursorJob: AtomicReference<Job?> = AtomicReference(null)
    private var lastCursor: Triple<String, Int, Int>? = null

    private val _state: MutableStateFlow<SessionState> = MutableStateFlow(
        SessionState(
            role = SessionRole.IDLE,
            localPeerId = localPeerId,
            localDisplayName = displayName,
            showPeerCursors = showPeerCursors,
        )
    )

    val state: StateFlow<SessionState> = _state

    init {
        peerRegistry.peers
            .onEach { peers -> _state.value = _state.value.copy(peers = peers) }
            .launchIn(scope)
        _state
            .distinctUntilChangedBy { it.role }
            .onEach { session ->
                if (session.role == SessionRole.IDLE) stopCursorTracking() else startCursorTracking()
            }
            .launchIn(scope)
    }

    fun startHosting(port: Int = NetUtil.DEFAULT_PAIR_PORT): Result<Unit> {
        PairLog.d("[HOST] startHosting requested (port=$port, peerId=$localPeerId)")
        if (_state.value.role != SessionRole.IDLE) {
            PairLog.w("[HOST] ignored: session already active (role=${_state.value.role})")
            return Result.failure(IllegalStateException("Session already active"))
        }
        val localIp = NetUtil.findLanIpv4()
        if (localIp == null) {
            PairLog.w("[HOST] aborting: no LAN address available")
            _state.value = _state.value.copy(lastError = "No network connection available")
            return Result.failure(IllegalStateException("No LAN address available"))
        }

        observer.start()
        fsObserver.start()
        editorService.addFileChangeListener(fileChangeListener)
        val token = generateToken()
        val server = PairWebSocketServer(
            port = port,
            expectedToken = token,
            callbacks = ServerCallbacksImpl(),
            onDecodeError = { logger.warn("PairPlugin: dropped malformed message (${it.message})") },
        )
        return runCatching {
            PairLog.d("[HOST] binding WebSocket server on 0.0.0.0:$port, advertising $localIp")
            server.start()
            serverRef.set(server)
            _state.value = _state.value.copy(
                role = SessionRole.HOST,
                localAddress = localIp,
                localPort = port,
                localToken = token,
                lastError = null,
            )
            PairLog.d("[HOST] hosting as $localIp:$port (tokenLen=${token.length})")
        }.onFailure {
            observer.stop()
            fsObserver.stop()
            logger.error("PairPlugin: failed to start server", it)
            PairLog.e("[HOST] server.start() failed", it)
            _state.value = _state.value.copy(lastError = "Could not start hosting (${it.message})")
        }
    }

    fun joinSession(host: String, port: Int, token: String): Result<Unit> {
        PairLog.d("[GUEST] joinSession requested → $host:$port (tokenLen=${token.length}, peerId=$localPeerId)")
        PairLog.d("[GUEST] resolving this device's own LAN address for subnet comparison:")
        val localIp = NetUtil.findLanIpv4()
        pendingLocalIp.set(localIp)
        PairLog.d("[GUEST] this device=$localIp, target host=$host — compare the first 3 octets (same /24?)")
        if (_state.value.role != SessionRole.IDLE || _state.value.connecting) {
            PairLog.w("[GUEST] ignored: role=${_state.value.role} connecting=${_state.value.connecting}")
            return Result.failure(IllegalStateException("Session already active"))
        }
        val client = PairWebSocketClient(
            host = host,
            port = port,
            token = token,
            callbacks = ClientCallbacksImpl(),
            onDecodeError = { logger.warn("PairPlugin: dropped malformed message (${it.message})") },
        )
        pendingRemote.set("$host:$port")
        _state.value = _state.value.copy(connecting = true, lastError = null)
        return runCatching {
            PairLog.d("[GUEST] opening socket to ws://$host:$port")
            client.connect()
            clientRef.set(client)
        }.onFailure {
            pendingRemote.set(null)
            logger.error("PairPlugin: failed to connect to $host:$port", it)
            PairLog.e("[GUEST] connect() threw synchronously for $host:$port", it)
            _state.value = _state.value.copy(connecting = false, lastError = "Could not connect to $host:$port")
        }
    }

    private fun handleConnectFailure(reason: String?) {
        if (!_state.value.connecting) return
        val target = pendingRemote.getAndSet(null)
        val localIp = pendingLocalIp.getAndSet(null)
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        observer.stop()
        fsObserver.stop()
        val message = diagnoseConnectFailure(localIp, target)
        logger.warn("PairPlugin: $message (reason=${reason ?: "no response"})")
        PairLog.w("[GUEST] connect failed for $target (reason=${reason ?: "none"}) → $message")
        _state.value = _state.value.copy(connecting = false, lastError = message)
    }

    private fun diagnoseConnectFailure(localIp: String?, target: String?): String {
        val targetHost = target?.substringBefore(":")
        if (localIp != null && targetHost != null && subnetOf(localIp) != subnetOf(targetHost)) {
            return "Different networks: this device is on ${subnetOf(localIp)}.x but the host is on " +
                "${subnetOf(targetHost)}.x. Join both devices to the same WiFi or the host's hotspot."
        }
        return "Could not reach ${target ?: "the host"}. Check you're on the same network (hotel/public WiFi often blocks this)."
    }

    private fun subnetOf(ip: String): String = ip.substringBeforeLast(".")

    fun stopSession() {
        observer.stop()
        fsObserver.stop()
        editorService.removeFileChangeListener(fileChangeListener)
        peerRegistry.clear()
        markers.clearAll()
        connToPeer.clear()
        applier.resetSequences()
        serverRef.getAndSet(null)?.let { server ->
            runCatching { server.stop(500) }
        }
        clientRef.getAndSet(null)?.let { client ->
            runCatching { client.close() }
        }
        _state.value = SessionState(
            role = SessionRole.IDLE,
            localPeerId = localPeerId,
            localDisplayName = displayName,
            showPeerCursors = showPeerCursors,
        )
    }

    private fun startCursorTracking() {
        if (cursorJob.get() != null) return
        lastCursor = null
        val job = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(CURSOR_POLL_INTERVAL_MS)
                val file = editorService.getCurrentFile() ?: continue
                val position = editorService.getCurrentCursorPosition() ?: continue
                val wirePath = pathMapper.toWire(file.absolutePath)
                val current = Triple(wirePath, position.line, position.column)
                if (current == lastCursor) continue
                if (suppression.isAutoCaret(wirePath, position.line, position.column)) continue
                lastCursor = current
                broadcast(ProtocolMessage.CursorMove(localPeerId, wirePath, position.line, position.column))
            }
        }
        cursorJob.set(job)
    }

    private fun stopCursorTracking() {
        cursorJob.getAndSet(null)?.cancel()
        lastCursor = null
    }

    private fun requestResync(wireFile: String) {
        if (!resyncPending.add(wireFile)) return
        when (_state.value.role) {
            SessionRole.HOST -> {
                broadcastResyncSnapshot(wireFile)
                resyncPending.remove(wireFile)
            }
            SessionRole.GUEST -> {
                PairLog.d("[RESYNC] guest requesting fresh snapshot from host for $wireFile")
                broadcast(ProtocolMessage.ResyncRequest(localPeerId, wireFile))
            }
            SessionRole.IDLE -> resyncPending.remove(wireFile)
        }
    }

    private fun broadcastResyncSnapshot(wireFile: String) {
        val localPath = pathMapper.toLocal(wireFile)
        val content = editorService.getFileContent(java.io.File(localPath))
        if (content == null) {
            PairLog.w("[RESYNC] cannot resync '$wireFile' — not available in editor")
            return
        }
        val nextSeq = observer.publishedSeq(wireFile) + 1
        observer.bumpSeq(wireFile, nextSeq)
        PairLog.d("[RESYNC] host broadcasting authoritative snapshot for $wireFile (${content.length} chars)")
        broadcast(ProtocolMessage.SyncSnapshot(localPeerId, wireFile, content, nextSeq))
        _state.value = _state.value.copy(outOfSync = false)
    }

    fun forceResyncFromMe() {
        if (_state.value.role != SessionRole.HOST) return
        val activeFile = editorService.getCurrentFile() ?: return
        val content = editorService.getCurrentFileContent() ?: return
        val wirePath = pathMapper.toWire(activeFile.absolutePath)
        val nextSeq = observer.publishedSeq(wirePath) + 1
        observer.bumpSeq(wirePath, nextSeq)
        broadcast(
            ProtocolMessage.SyncSnapshot(
                peerId = localPeerId,
                file = wirePath,
                content = content,
                seq = nextSeq,
            )
        )
        _state.value = _state.value.copy(outOfSync = false)
    }

    fun requestProjectFromHost(): Result<Unit> {
        if (_state.value.role != SessionRole.GUEST) {
            return Result.failure(IllegalStateException("Only a guest can pull the project"))
        }
        broadcast(ProtocolMessage.ManifestRequest(localPeerId))
        return Result.success(Unit)
    }

    fun confirmOpenPulledProject() {
        val root = pendingProjectRoot.getAndSet(null) ?: return
        _state.value = _state.value.copy(pendingProjectPath = null)
        val service = projectService
        if (service == null) {
            PairLog.w("[PULL] no IdeProjectService available — cannot open ${root.absolutePath}")
            _state.value = _state.value.copy(lastError = "Files synced to ${root.absolutePath}. Open it manually (no project service).")
            return
        }
        PairLog.d("[PULL] calling openProject(${root.absolutePath}) exists=${root.exists()} isDir=${root.isDirectory}")
        runCatching { service.openProject(root) }
            .onSuccess { opened ->
                PairLog.d("[PULL] openProject returned $opened")
                if (!opened) {
                    _state.value = _state.value.copy(
                        lastError = "Synced to ${root.absolutePath}. IDE returned false — its build may predate the openProject API, or there's no foreground activity. Open manually.",
                    )
                }
            }
            .onFailure { e ->
                PairLog.e("[PULL] openProject threw ${e.javaClass.simpleName}: ${e.message}", e)
                _state.value = _state.value.copy(
                    lastError = "Synced to ${root.absolutePath}. openProject error: ${e.message}. Open manually.",
                )
            }
    }

    fun dismissPulledProject() {
        pendingProjectRoot.set(null)
        _state.value = _state.value.copy(pendingProjectPath = null)
    }

    fun dispose() {
        stopSession()
        scope.cancel()
    }

    private fun generateToken(): String {
        return SecureRandom().nextInt(PIN_BOUND).toString().padStart(PIN_LENGTH, '0')
    }

    private fun handleIncoming(origin: WebSocket?, message: ProtocolMessage) {
        if (message.peerId == localPeerId) return
        when (message) {
            is ProtocolMessage.Hello -> {
                PairLog.d("[HANDSHAKE] Hello from ${message.displayName} (peerId=${message.peerId}, proto=${message.protocolVersion})")
                if (message.protocolVersion != ProtocolMessage.PROTOCOL_VERSION) {
                    logger.warn("PairPlugin: rejecting peer with protocol v${message.protocolVersion} (local v${ProtocolMessage.PROTOCOL_VERSION})")
                    _state.value = _state.value.copy(lastError = "Incompatible Pair protocol version")
                    return
                }
                if (origin != null) connToPeer[origin] = message.peerId
                peerRegistry.upsertFromHello(message, isHost = (origin != null))
                if (_state.value.role == SessionRole.HOST && origin != null) {
                    serverRef.get()?.sendTo(
                        origin,
                        ProtocolMessage.Hello(
                            peerId = localPeerId,
                            displayName = displayName,
                            colorIndex = localColorIndex,
                        )
                    )
                }
            }
            is ProtocolMessage.Goodbye -> {
                peerRegistry.remove(message.peerId)
                markers.remove(message.peerId)
            }
            is ProtocolMessage.Edit -> {
                val currentSeq = applier.currentSeq(message.file)
                if (message.baseSeq != currentSeq && _state.value.role == SessionRole.GUEST) {
                    _state.value = _state.value.copy(outOfSync = true)
                }
                applier.applyEdit(message)
                observer.bumpSeq(message.file, message.seq)
                showMarker(message.peerId, message.file, message.startLine, message.startColumn)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.CursorMove -> {
                peerRegistry.updateCursor(message.peerId, message.file, message.line, message.column)
                showMarker(message.peerId, message.file, message.line, message.column)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileOpened -> {
                applier.applySnapshot(
                    ProtocolMessage.SyncSnapshot(
                        peerId = message.peerId,
                        file = message.file,
                        content = message.content,
                        seq = message.seq,
                    )
                )
                peerRegistry.updateFocus(message.peerId, message.file)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileClosed -> {
                peerRegistry.updateFocus(message.peerId, null)
                markers.remove(message.peerId)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileFocused -> {
                peerRegistry.updateFocus(message.peerId, message.file)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.SyncSnapshot -> {
                applier.applySnapshot(message)
                resyncPending.remove(message.file)
                _state.value = _state.value.copy(outOfSync = false)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileCreated -> {
                fsApplier.applyCreated(message)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileDeleted -> {
                fsApplier.applyDeleted(message)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.FileRenamed -> {
                fsApplier.applyRenamed(message)
                if (_state.value.role == SessionRole.HOST) {
                    relayFromHost(origin, message)
                }
            }
            is ProtocolMessage.ManifestRequest -> {
                if (_state.value.role == SessionRole.HOST && origin != null) {
                    serverRef.get()?.sendTo(origin, syncCoordinator.buildManifest())
                }
            }
            is ProtocolMessage.ProjectManifest -> {
                val request = syncCoordinator.onManifest(message)
                if (request.paths.isNotEmpty()) {
                    broadcast(request)
                }
            }
            is ProtocolMessage.FileRequest -> {
                if (_state.value.role == SessionRole.HOST && origin != null) {
                    val server = serverRef.get() ?: return
                    syncCoordinator.serveRequest(
                        paths = message.paths,
                        sendBinary = { server.sendBinaryTo(origin, it) },
                        sendComplete = {
                            server.sendTo(origin, ProtocolMessage.FileTransferComplete(localPeerId))
                        },
                    )
                }
            }
            is ProtocolMessage.FileTransferComplete -> {
                syncCoordinator.onTransferComplete()
            }
            is ProtocolMessage.ResyncRequest -> {
                if (_state.value.role == SessionRole.HOST) {
                    PairLog.d("[RESYNC] host received request for ${message.file} — sending snapshot")
                    broadcastResyncSnapshot(message.file)
                }
            }
        }
    }

    private fun showMarker(peerId: String, wireFile: String, line: Int, column: Int) {
        if (!showPeerCursors) {
            PairLog.d("[MARKERS] suppressed — 'Show others' cursors' is OFF")
            return
        }
        val peer = peerRegistry.peer(peerId) ?: run {
            PairLog.d("[MARKERS] no peer registered for $peerId — skipping marker")
            return
        }
        PairLog.d("[MARKERS] show ${peer.displayName} at $wireFile $line:$column")
        markers.show(peerId, peer.displayName, peer.colorIndex, wireFile, line, column)
    }

    private fun relayFromHost(origin: WebSocket?, message: ProtocolMessage) {
        serverRef.get()?.broadcastExcept(origin, message)
    }

    private fun broadcast(message: ProtocolMessage) {
        serverRef.get()?.broadcastExcept(null, message)
        clientRef.get()?.sendMessage(message)
    }

    private fun sendHelloToServer() {
        clientRef.get()?.sendMessage(
            ProtocolMessage.Hello(
                peerId = localPeerId,
                displayName = displayName,
                colorIndex = localColorIndex,
            )
        )
    }

    private inner class OutboundDispatcher : OutboundSink {
        override fun send(message: ProtocolMessage) {
            broadcast(message)
        }
    }

    private inner class ServerCallbacksImpl : PairWebSocketServer.ServerCallbacks {
        override fun onClientConnected(connection: WebSocket) {
            logger.info("PairPlugin: client connected from ${connection.remoteSocketAddress}")
            PairLog.d("[SERVER] client accepted from ${connection.remoteSocketAddress} → sending Hello + snapshot")
            serverRef.get()?.sendTo(
                connection,
                ProtocolMessage.Hello(
                    peerId = localPeerId,
                    displayName = displayName,
                    colorIndex = localColorIndex,
                )
            )
            val activeFile = editorService.getCurrentFile()
            val content = editorService.getCurrentFileContent()
            if (activeFile != null && content != null) {
                val wirePath = pathMapper.toWire(activeFile.absolutePath)
                serverRef.get()?.sendTo(
                    connection,
                    ProtocolMessage.SyncSnapshot(
                        peerId = localPeerId,
                        file = wirePath,
                        content = content,
                        seq = observer.publishedSeq(wirePath),
                    )
                )
            }
        }

        override fun onClientDisconnected(connection: WebSocket, reason: String?) {
            logger.info("PairPlugin: client disconnected: $reason")
            val peerId = connToPeer.remove(connection) ?: return
            peerRegistry.remove(peerId)
            markers.remove(peerId)
            relayFromHost(connection, ProtocolMessage.Goodbye(peerId))
        }

        override fun onMessageReceived(connection: WebSocket, message: ProtocolMessage) {
            handleIncoming(connection, message)
        }

        override fun onServerStarted(port: Int) {
            logger.info("PairPlugin: server listening on $port")
        }

        override fun onError(error: Throwable) {
            logger.error("PairPlugin: server error", error)
            _state.value = _state.value.copy(lastError = error.message)
        }
    }

    private inner class ClientCallbacksImpl : PairWebSocketClient.ClientCallbacks {
        override fun onConnected() {
            logger.info("PairPlugin: connected to host")
            PairLog.d("[CLIENT] socket open → sending Hello, switching to GUEST")
            observer.start()
            fsObserver.start()
            editorService.addFileChangeListener(fileChangeListener)
            _state.value = _state.value.copy(
                role = SessionRole.GUEST,
                connecting = false,
                remoteAddress = pendingRemote.getAndSet(null),
                lastError = null,
            )
            sendHelloToServer()
        }

        override fun onDisconnected(reason: String?) {
            logger.info("PairPlugin: disconnected from host: $reason")
            PairLog.d("[CLIENT] disconnected (reason=${reason ?: "none"}, connecting=${_state.value.connecting}, role=${_state.value.role})")
            if (_state.value.connecting) {
                handleConnectFailure(reason)
                return
            }
            if (_state.value.role != SessionRole.GUEST) return
            observer.stop()
            fsObserver.stop()
            peerRegistry.clear()
            markers.clearAll()
            clientRef.set(null)
            _state.value = SessionState(
                role = SessionRole.IDLE,
                localPeerId = localPeerId,
                localDisplayName = displayName,
                showPeerCursors = showPeerCursors,
                lastError = reason ?: "Disconnected from host",
            )
        }

        override fun onMessageReceived(message: ProtocolMessage) {
            handleIncoming(null, message)
        }

        override fun onBinaryReceived(data: java.nio.ByteBuffer) {
            syncCoordinator.onChunk(data)
        }

        override fun onError(error: Throwable) {
            logger.error("PairPlugin: client error", error)
            PairLog.e("[CLIENT] onError ${error.javaClass.simpleName}: ${error.message}", error)
            if (_state.value.connecting) {
                handleConnectFailure(error.message)
            } else {
                _state.value = _state.value.copy(lastError = error.message)
            }
        }
    }
}
