package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.IdeFileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.util.Base64

class FileSystemApplier(
    private val fileService: IdeFileService,
    private val scope: CoroutineScope,
    private val suppression: LoopbackSuppression,
    private val pathMapper: PathMapper,
    private val logger: PluginLogger,
    private val onOutOfSync: () -> Unit,
) {

    fun applyCreated(message: ProtocolMessage.FileCreated) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val local = pathMapper.toLocalChecked(message.file) ?: run {
                    logger.warn("PairPlugin: rejected create for unsafe path '${message.file}'")
                    onOutOfSync()
                    return@withContext
                }
                if (message.isDirectory) {
                    suppression.enter()
                    try {
                        local.mkdirs()
                        EventBus.getDefault().post(FileCreationEvent(local))
                    } finally {
                        suppression.exit()
                    }
                    return@withContext
                }
                val encoded = message.contentBase64 ?: run {
                    logger.warn("PairPlugin: create for '${message.file}' arrived without content — flagging out of sync")
                    onOutOfSync()
                    return@withContext
                }
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: run {
                    logger.warn("PairPlugin: malformed content for created file '${message.file}'")
                    onOutOfSync()
                    return@withContext
                }
                suppression.enter()
                try {
                    if (fileService.writeBinary(local, bytes)) {
                        EventBus.getDefault().post(FileCreationEvent(local))
                    } else {
                        logger.warn("PairPlugin: failed to write created file '${message.file}'")
                        onOutOfSync()
                    }
                } finally {
                    suppression.exit()
                }
            }
        }
    }

    fun applyDeleted(message: ProtocolMessage.FileDeleted) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val local = pathMapper.toLocalChecked(message.file) ?: run {
                    logger.warn("PairPlugin: rejected delete for unsafe path '${message.file}'")
                    return@withContext
                }
                if (!local.exists()) return@withContext
                suppression.enter()
                try {
                    if (fileService.delete(local)) {
                        EventBus.getDefault().post(FileDeletionEvent(local))
                    } else {
                        logger.warn("PairPlugin: failed to delete '${message.file}'")
                    }
                } finally {
                    suppression.exit()
                }
            }
        }
    }

    fun applyRenamed(message: ProtocolMessage.FileRenamed) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val from = pathMapper.toLocalChecked(message.from) ?: run {
                    logger.warn("PairPlugin: rejected rename source '${message.from}'")
                    onOutOfSync()
                    return@withContext
                }
                val to = pathMapper.toLocalChecked(message.to) ?: run {
                    logger.warn("PairPlugin: rejected rename target '${message.to}'")
                    onOutOfSync()
                    return@withContext
                }
                if (!from.exists()) {
                    logger.warn("PairPlugin: rename source '${message.from}' missing locally — flagging out of sync")
                    onOutOfSync()
                    return@withContext
                }
                val bytes = runCatching { from.readBytes() }.getOrNull() ?: run {
                    logger.warn("PairPlugin: could not read rename source '${message.from}'")
                    onOutOfSync()
                    return@withContext
                }
                suppression.enter()
                try {
                    if (fileService.writeBinary(to, bytes) && fileService.delete(from)) {
                        EventBus.getDefault().post(FileRenameEvent(from, to))
                    } else {
                        logger.warn("PairPlugin: failed to apply rename '${message.from}' -> '${message.to}'")
                        onOutOfSync()
                    }
                } finally {
                    suppression.exit()
                }
            }
        }
    }
}
