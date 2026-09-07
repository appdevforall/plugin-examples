package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.FileChunkCodec
import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.IdeFileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import com.appdevforall.pair.plugin.util.PairLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ProjectSyncCoordinator(
    private val localPeerId: String,
    private val manifestBuilder: ProjectManifestBuilder,
    private val fileService: IdeFileService,
    private val pathMapper: PathMapper,
    private val suppression: LoopbackSuppression,
    private val scope: CoroutineScope,
    private val logger: PluginLogger,
    private val onProgress: (received: Int, total: Int) -> Unit,
    private val onComplete: (projectRoot: File?) -> Unit,
) {

    private val expectedSizes: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val buffers: ConcurrentHashMap<String, ByteArrayOutputStream> = ConcurrentHashMap()
    private val completedPaths: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var totalRequested: Int = 0

    // When pulling, the guest writes the incoming project into a fresh directory (a sibling of
    // its current project, named after the host's project) rather than nesting it inside the
    // currently-open project. Set per pull from the manifest; null falls back to path-mapping.
    @Volatile private var pullTargetRoot: File? = null

    fun buildManifest(): ProtocolMessage.ProjectManifest =
        ProtocolMessage.ProjectManifest(
            peerId = localPeerId,
            entries = manifestBuilder.build(),
            projectName = manifestBuilder.rootDir()?.name.orEmpty(),
        )

    fun serveRequest(
        paths: List<String>,
        sendBinary: (ByteArray) -> Unit,
        sendComplete: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            for (relative in paths) {
                val local = pathMapper.toLocalChecked(relative)
                if (local == null || !local.isFile) {
                    logger.warn("PairPlugin: cannot serve '$relative' (unsafe or missing)")
                    continue
                }
                runCatching {
                    FileInputStream(local).use { input ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        var offset = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sendBinary(FileChunkCodec.encode(relative, offset, buffer, read))
                            offset += read
                        }
                        if (offset == 0L) {
                            sendBinary(FileChunkCodec.encode(relative, 0L, EMPTY, 0))
                        }
                    }
                }.onFailure { logger.warn("PairPlugin: failed serving '$relative' (${it.message})") }
            }
            sendComplete()
        }
    }

    fun onManifest(manifest: ProtocolMessage.ProjectManifest): ProtocolMessage.FileRequest {
        reset()
        val currentRoot = manifestBuilder.rootDir()
        pullTargetRoot = resolvePullTargetRoot(manifest.projectName)
        PairLog.d("[PULL] manifest: ${manifest.entries.size} files, project='${manifest.projectName}'")
        PairLog.d("[PULL] guest currentProject=${currentRoot?.absolutePath ?: "(none open)"} base=${currentRoot?.parentFile?.absolutePath ?: "(none)"}")
        PairLog.d("[PULL] writing into target=${pullTargetRoot?.absolutePath ?: "(falling back to current project via PathMapper)"}")
        val needed = ArrayList<String>()
        for (entry in manifest.entries) {
            val local = resolveLocalForPull(entry.path)
            val alreadyHave = local != null && local.isFile && manifestBuilder.sha256(local) == entry.sha256
            if (!alreadyHave) {
                needed.add(entry.path)
                expectedSizes[entry.path] = entry.size
            }
        }
        totalRequested = needed.size
        onProgress(0, totalRequested)
        if (needed.isEmpty()) onComplete(pullTargetRoot)
        return ProtocolMessage.FileRequest(localPeerId, needed)
    }

    private fun resolvePullTargetRoot(projectName: String): File? {
        val base = manifestBuilder.rootDir()?.parentFile ?: return null
        val safeName = File(projectName).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
        return File(base, safeName)
    }

    private fun resolveLocalForPull(relative: String): File? {
        val root = pullTargetRoot ?: return pathMapper.toLocalChecked(relative)
        if (File(relative).isAbsolute) return null
        return runCatching {
            val rootFile = root.canonicalFile
            val resolved = File(rootFile, relative).canonicalFile
            val boundary = rootFile.path + File.separator
            if (resolved.path == rootFile.path || resolved.path.startsWith(boundary)) resolved else null
        }.getOrNull()
    }

    fun onChunk(buffer: ByteBuffer) {
        val chunk = runCatching { FileChunkCodec.decode(buffer) }.getOrNull() ?: return
        val expected = expectedSizes[chunk.path] ?: return
        val sink = buffers.getOrPut(chunk.path) { ByteArrayOutputStream() }
        val complete: ByteArray?
        synchronized(sink) {
            sink.write(chunk.data)
            complete = if (sink.size().toLong() >= expected) {
                buffers.remove(chunk.path)
                sink.toByteArray()
            } else {
                null
            }
        }
        if (complete != null) materialize(chunk.path, complete)
    }

    fun onTransferComplete() {
        onComplete(pullTargetRoot)
    }

    fun reset() {
        expectedSizes.clear()
        buffers.clear()
        completedPaths.clear()
        totalRequested = 0
    }

    private fun materialize(relative: String, bytes: ByteArray) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val local = resolveLocalForPull(relative) ?: run {
                    logger.warn("PairPlugin: rejected materialize for unsafe path '$relative'")
                    return@withContext
                }
                suppression.enter()
                try {
                    if (fileService.writeBinary(local, bytes)) {
                        PairLog.d("[PULL] wrote ${local.absolutePath} (${bytes.size} bytes)")
                        EventBus.getDefault().post(FileCreationEvent(local))
                    } else {
                        PairLog.w("[PULL] writeBinary FAILED for ${local.absolutePath}")
                        logger.warn("PairPlugin: failed to materialize '$relative'")
                    }
                } finally {
                    suppression.exit()
                }
                if (completedPaths.add(relative)) {
                    onProgress(completedPaths.size, totalRequested)
                }
            }
        }
    }

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private val EMPTY = ByteArray(0)
    }
}
