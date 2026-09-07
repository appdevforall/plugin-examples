package com.appdevforall.pair.plugin.domain

import com.itsaky.androidide.plugins.services.IdeEditorService
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RemoteMarkerController(
    private val editorService: IdeEditorService,
    private val pathMapper: PathMapper,
) {

    private val peerFile: ConcurrentHashMap<String, File> = ConcurrentHashMap()

    fun show(peerId: String, peerName: String, colorIndex: Int, wireFile: String, line: Int, column: Int) {
        val local = File(pathMapper.toLocal(wireFile))
        val previous = peerFile.put(peerId, local)
        if (previous != null && previous != local) {
            runCatching { editorService.hidePeerCursor(previous, peerId) }
        }
        runCatching {
            editorService.showPeerCursor(local, line, column, peerId, peerName, peerColorArgb(colorIndex))
        }
    }

    fun remove(peerId: String) {
        val file = peerFile.remove(peerId) ?: return
        runCatching { editorService.hidePeerCursor(file, peerId) }
    }

    fun clearAll() {
        val files = peerFile.values.toSet()
        peerFile.clear()
        files.forEach { file -> runCatching { editorService.clearPeerCursors(file) } }
    }

    private fun peerColorArgb(colorIndex: Int): Int {
        val safe = ((colorIndex % PALETTE.size) + PALETTE.size) % PALETTE.size
        return PALETTE[safe]
    }

    private companion object {
        val PALETTE = intArrayOf(
            0xFF0F766E.toInt(),
            0xFFC2410C.toInt(),
            0xFF1E40AF.toInt(),
            0xFFA16207.toInt(),
            0xFF7E22CE.toInt(),
        )
    }
}
