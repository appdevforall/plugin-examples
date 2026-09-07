package com.appdevforall.pair.plugin.data

sealed interface ProtocolMessage {
    val peerId: String

    data class Hello(
        override val peerId: String,
        val displayName: String,
        val colorIndex: Int,
        val protocolVersion: Int = PROTOCOL_VERSION,
    ) : ProtocolMessage

    data class Goodbye(
        override val peerId: String,
    ) : ProtocolMessage

    data class Edit(
        override val peerId: String,
        val file: String,
        val seq: Long,
        val baseSeq: Long,
        val op: EditOp,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val text: String,
    ) : ProtocolMessage

    data class CursorMove(
        override val peerId: String,
        val file: String,
        val line: Int,
        val column: Int,
    ) : ProtocolMessage

    data class FileOpened(
        override val peerId: String,
        val file: String,
        val content: String,
        val seq: Long,
    ) : ProtocolMessage

    data class FileClosed(
        override val peerId: String,
        val file: String,
    ) : ProtocolMessage

    data class FileFocused(
        override val peerId: String,
        val file: String,
    ) : ProtocolMessage

    data class SyncSnapshot(
        override val peerId: String,
        val file: String,
        val content: String,
        val seq: Long,
    ) : ProtocolMessage

    data class FileCreated(
        override val peerId: String,
        val file: String,
        val isDirectory: Boolean,
        val contentBase64: String?,
    ) : ProtocolMessage

    data class FileDeleted(
        override val peerId: String,
        val file: String,
    ) : ProtocolMessage

    data class FileRenamed(
        override val peerId: String,
        val from: String,
        val to: String,
    ) : ProtocolMessage

    data class ManifestRequest(
        override val peerId: String,
    ) : ProtocolMessage

    data class ProjectManifest(
        override val peerId: String,
        val entries: List<ManifestEntry>,
        val projectName: String = "",
    ) : ProtocolMessage

    data class FileRequest(
        override val peerId: String,
        val paths: List<String>,
    ) : ProtocolMessage

    data class FileTransferComplete(
        override val peerId: String,
    ) : ProtocolMessage

    data class ResyncRequest(
        override val peerId: String,
        val file: String,
    ) : ProtocolMessage

    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}

enum class EditOp {
    INSERT,
    DELETE,
    REPLACE,
}
