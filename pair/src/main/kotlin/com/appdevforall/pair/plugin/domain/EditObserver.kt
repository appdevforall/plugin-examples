package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.EditOp
import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSelectedEvent
import com.appdevforall.pair.plugin.util.PairLog
import com.itsaky.androidide.plugins.services.IdeEditorService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EditObserver(
    private val localPeerId: String,
    private val editorService: IdeEditorService,
    private val suppression: LoopbackSuppression,
    private val pathMapper: PathMapper,
    private val outbound: OutboundSink,
) {

    private val seqByFile: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
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

    fun publishedSeq(file: String): Long = seqByFile.getOrPut(file) { AtomicLong(0L) }.get()

    fun bumpSeq(file: String, to: Long) {
        seqByFile.getOrPut(file) { AtomicLong(0L) }.set(to)
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    fun onDocumentChange(event: DocumentChangeEvent) {
        if (suppression.isSuppressed()) {
            PairLog.d("[EDIT] suppressed echo (counter) for ${event.changedFile}")
            return
        }
        val file = pathMapper.toWire(event.changedFile.toString())
        val currentContent = editorService.getFileContent(File(event.changedFile.toString()))
        if (suppression.isEchoOfApplied(file, currentContent)) {
            PairLog.d("[EDIT] suppressed echo (content matches last applied) for $file")
            return
        }
        val counter = seqByFile.getOrPut(file) { AtomicLong(0L) }
        val baseSeq = counter.get()
        val nextSeq = counter.incrementAndGet()
        val range = event.changeRange
        val op = when (event.changeType) {
            ChangeType.INSERT -> EditOp.INSERT
            ChangeType.DELETE -> EditOp.DELETE
            ChangeType.NEW_TEXT -> EditOp.REPLACE
        }
        val text = when (event.changeType) {
            ChangeType.INSERT -> event.changedText
            ChangeType.DELETE -> ""
            ChangeType.NEW_TEXT -> event.newText ?: event.changedText
        }
        PairLog.d("[EDIT] outbound $op $file @${range.start.line}:${range.start.column}..${range.end.line}:${range.end.column} textLen=${text.length} seq=$nextSeq")
        outbound.send(
            ProtocolMessage.Edit(
                peerId = localPeerId,
                file = file,
                seq = nextSeq,
                baseSeq = baseSeq,
                op = op,
                startLine = range.start.line,
                startColumn = range.start.column,
                endLine = range.end.line,
                endColumn = range.end.column,
                text = text,
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    fun onDocumentOpen(event: DocumentOpenEvent) {
        if (suppression.isSuppressed()) return
        val file = pathMapper.toWire(event.openedFile.toString())
        seqByFile.getOrPut(file) { AtomicLong(0L) }.set(0L)
        PairLog.d("[EDIT] outbound FileOpened $file contentLen=${event.text.length}")
        outbound.send(
            ProtocolMessage.FileOpened(
                peerId = localPeerId,
                file = file,
                content = event.text,
                seq = 0L,
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    fun onDocumentClose(event: DocumentCloseEvent) {
        if (suppression.isSuppressed()) return
        outbound.send(
            ProtocolMessage.FileClosed(
                peerId = localPeerId,
                file = pathMapper.toWire(event.closedFile.toString()),
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    fun onDocumentSelected(event: DocumentSelectedEvent) {
        if (suppression.isSuppressed()) return
        val file = pathMapper.toWire(event.selectedFile.toString())
        outbound.send(
            ProtocolMessage.FileFocused(
                peerId = localPeerId,
                file = file,
            )
        )
        val pos = editorService.getCurrentCursorPosition()
        if (pos != null) {
            outbound.send(
                ProtocolMessage.CursorMove(
                    peerId = localPeerId,
                    file = file,
                    line = pos.line,
                    column = pos.column,
                )
            )
        }
    }
}
