package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.plugins.PluginLogger
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Base64

class FileSystemObserver(
    private val localPeerId: String,
    private val pathMapper: PathMapper,
    private val suppression: LoopbackSuppression,
    private val outbound: OutboundSink,
    private val logger: PluginLogger,
) {

    private var registered: Boolean = false

    fun start() {
        if (registered) return
        EventBus.getDefault().register(this)
        registered = true
    }

    fun stop() {
        if (!registered) return
        EventBus.getDefault().unregister(this)
        registered = false
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFileCreated(event: FileCreationEvent) {
        if (suppression.isSuppressed()) return
        val source = event.file
        val wire = pathMapper.toWire(source.absolutePath)
        if (source.isDirectory) {
            outbound.send(ProtocolMessage.FileCreated(localPeerId, wire, isDirectory = true, contentBase64 = null))
            return
        }
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: run {
            logger.warn("PairPlugin: could not read created file '${source.name}' for sync")
            return
        }
        if (bytes.size > MAX_INLINE_FILE_BYTES) {
            logger.warn("PairPlugin: created file '${source.name}' (${bytes.size} bytes) exceeds inline cap; sending without content")
            outbound.send(ProtocolMessage.FileCreated(localPeerId, wire, isDirectory = false, contentBase64 = null))
            return
        }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        outbound.send(ProtocolMessage.FileCreated(localPeerId, wire, isDirectory = false, contentBase64 = encoded))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFileDeleted(event: FileDeletionEvent) {
        if (suppression.isSuppressed()) return
        outbound.send(
            ProtocolMessage.FileDeleted(localPeerId, pathMapper.toWire(event.file.absolutePath)),
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFileRenamed(event: FileRenameEvent) {
        if (suppression.isSuppressed()) return
        outbound.send(
            ProtocolMessage.FileRenamed(
                peerId = localPeerId,
                from = pathMapper.toWire(event.file.absolutePath),
                to = pathMapper.toWire(event.newFile.absolutePath),
            ),
        )
    }

    companion object {
        const val MAX_INLINE_FILE_BYTES: Int = 1 * 1024 * 1024
    }
}
