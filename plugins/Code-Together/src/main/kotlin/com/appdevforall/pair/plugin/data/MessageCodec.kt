package com.appdevforall.pair.plugin.data

import org.json.JSONArray
import org.json.JSONObject

object MessageCodec {

    private const val KEY_TYPE = "t"
    private const val KEY_PEER = "pid"
    private const val KEY_NAME = "name"
    private const val KEY_COLOR = "color"
    private const val KEY_PROTO = "proto"
    private const val KEY_FILE = "f"
    private const val KEY_SEQ = "s"
    private const val KEY_BASE_SEQ = "bs"
    private const val KEY_OP = "op"
    private const val KEY_START_LINE = "sl"
    private const val KEY_START_COL = "sc"
    private const val KEY_END_LINE = "el"
    private const val KEY_END_COL = "ec"
    private const val KEY_TEXT = "tx"
    private const val KEY_LINE = "l"
    private const val KEY_COL = "c"
    private const val KEY_CONTENT = "ct"
    private const val KEY_IS_DIR = "dir"
    private const val KEY_FROM = "fr"
    private const val KEY_TO = "to"
    private const val KEY_ENTRIES = "en"
    private const val KEY_PATHS = "ps"
    private const val KEY_PATH = "p"
    private const val KEY_SIZE = "sz"
    private const val KEY_HASH = "h"
    private const val KEY_PROJECT_NAME = "pn"

    private const val T_HELLO = "hi"
    private const val T_GOODBYE = "bye"
    private const val T_EDIT = "edit"
    private const val T_CURSOR = "cur"
    private const val T_FILE_OPEN = "fo"
    private const val T_FILE_CLOSE = "fc"
    private const val T_FILE_FOCUS = "ff"
    private const val T_SYNC = "sync"
    private const val T_FILE_CREATE = "fcr"
    private const val T_FILE_DELETE = "fdl"
    private const val T_FILE_RENAME = "frn"
    private const val T_MANIFEST_REQUEST = "mreq"
    private const val T_MANIFEST = "man"
    private const val T_FILE_REQUEST = "freq"
    private const val T_TRANSFER_DONE = "tdone"
    private const val T_RESYNC_REQUEST = "rreq"

    fun encode(msg: ProtocolMessage): String {
        val json = JSONObject()
        json.put(KEY_PEER, msg.peerId)
        when (msg) {
            is ProtocolMessage.Hello -> {
                json.put(KEY_TYPE, T_HELLO)
                json.put(KEY_NAME, msg.displayName)
                json.put(KEY_COLOR, msg.colorIndex)
                json.put(KEY_PROTO, msg.protocolVersion)
            }
            is ProtocolMessage.Goodbye -> {
                json.put(KEY_TYPE, T_GOODBYE)
            }
            is ProtocolMessage.Edit -> {
                json.put(KEY_TYPE, T_EDIT)
                json.put(KEY_FILE, msg.file)
                json.put(KEY_SEQ, msg.seq)
                json.put(KEY_BASE_SEQ, msg.baseSeq)
                json.put(KEY_OP, msg.op.name)
                json.put(KEY_START_LINE, msg.startLine)
                json.put(KEY_START_COL, msg.startColumn)
                json.put(KEY_END_LINE, msg.endLine)
                json.put(KEY_END_COL, msg.endColumn)
                json.put(KEY_TEXT, msg.text)
            }
            is ProtocolMessage.CursorMove -> {
                json.put(KEY_TYPE, T_CURSOR)
                json.put(KEY_FILE, msg.file)
                json.put(KEY_LINE, msg.line)
                json.put(KEY_COL, msg.column)
            }
            is ProtocolMessage.FileOpened -> {
                json.put(KEY_TYPE, T_FILE_OPEN)
                json.put(KEY_FILE, msg.file)
                json.put(KEY_CONTENT, msg.content)
                json.put(KEY_SEQ, msg.seq)
            }
            is ProtocolMessage.FileClosed -> {
                json.put(KEY_TYPE, T_FILE_CLOSE)
                json.put(KEY_FILE, msg.file)
            }
            is ProtocolMessage.FileFocused -> {
                json.put(KEY_TYPE, T_FILE_FOCUS)
                json.put(KEY_FILE, msg.file)
            }
            is ProtocolMessage.SyncSnapshot -> {
                json.put(KEY_TYPE, T_SYNC)
                json.put(KEY_FILE, msg.file)
                json.put(KEY_CONTENT, msg.content)
                json.put(KEY_SEQ, msg.seq)
            }
            is ProtocolMessage.FileCreated -> {
                json.put(KEY_TYPE, T_FILE_CREATE)
                json.put(KEY_FILE, msg.file)
                json.put(KEY_IS_DIR, msg.isDirectory)
                if (msg.contentBase64 != null) json.put(KEY_CONTENT, msg.contentBase64)
            }
            is ProtocolMessage.FileDeleted -> {
                json.put(KEY_TYPE, T_FILE_DELETE)
                json.put(KEY_FILE, msg.file)
            }
            is ProtocolMessage.FileRenamed -> {
                json.put(KEY_TYPE, T_FILE_RENAME)
                json.put(KEY_FROM, msg.from)
                json.put(KEY_TO, msg.to)
            }
            is ProtocolMessage.ManifestRequest -> {
                json.put(KEY_TYPE, T_MANIFEST_REQUEST)
            }
            is ProtocolMessage.ProjectManifest -> {
                json.put(KEY_TYPE, T_MANIFEST)
                json.put(KEY_PROJECT_NAME, msg.projectName)
                val array = JSONArray()
                for (entry in msg.entries) {
                    array.put(
                        JSONObject()
                            .put(KEY_PATH, entry.path)
                            .put(KEY_SIZE, entry.size)
                            .put(KEY_HASH, entry.sha256),
                    )
                }
                json.put(KEY_ENTRIES, array)
            }
            is ProtocolMessage.FileRequest -> {
                json.put(KEY_TYPE, T_FILE_REQUEST)
                json.put(KEY_PATHS, JSONArray(msg.paths))
            }
            is ProtocolMessage.FileTransferComplete -> {
                json.put(KEY_TYPE, T_TRANSFER_DONE)
            }
            is ProtocolMessage.ResyncRequest -> {
                json.put(KEY_TYPE, T_RESYNC_REQUEST)
                json.put(KEY_FILE, msg.file)
            }
        }
        return json.toString()
    }

    fun decode(raw: String): ProtocolMessage {
        val json = JSONObject(raw)
        val type = json.getString(KEY_TYPE)
        val peerId = json.getString(KEY_PEER)
        return when (type) {
            T_HELLO -> ProtocolMessage.Hello(
                peerId = peerId,
                displayName = json.getString(KEY_NAME),
                colorIndex = json.getInt(KEY_COLOR),
                protocolVersion = json.getInt(KEY_PROTO),
            )
            T_GOODBYE -> ProtocolMessage.Goodbye(peerId)
            T_EDIT -> ProtocolMessage.Edit(
                peerId = peerId,
                file = json.getString(KEY_FILE),
                seq = json.getLong(KEY_SEQ),
                baseSeq = json.getLong(KEY_BASE_SEQ),
                op = EditOp.valueOf(json.getString(KEY_OP)),
                startLine = json.getInt(KEY_START_LINE),
                startColumn = json.getInt(KEY_START_COL),
                endLine = json.getInt(KEY_END_LINE),
                endColumn = json.getInt(KEY_END_COL),
                text = json.getString(KEY_TEXT),
            )
            T_CURSOR -> ProtocolMessage.CursorMove(
                peerId = peerId,
                file = json.getString(KEY_FILE),
                line = json.getInt(KEY_LINE),
                column = json.getInt(KEY_COL),
            )
            T_FILE_OPEN -> ProtocolMessage.FileOpened(
                peerId = peerId,
                file = json.getString(KEY_FILE),
                content = json.getString(KEY_CONTENT),
                seq = json.getLong(KEY_SEQ),
            )
            T_FILE_CLOSE -> ProtocolMessage.FileClosed(
                peerId = peerId,
                file = json.getString(KEY_FILE),
            )
            T_FILE_FOCUS -> ProtocolMessage.FileFocused(
                peerId = peerId,
                file = json.getString(KEY_FILE),
            )
            T_SYNC -> ProtocolMessage.SyncSnapshot(
                peerId = peerId,
                file = json.getString(KEY_FILE),
                content = json.getString(KEY_CONTENT),
                seq = json.getLong(KEY_SEQ),
            )
            T_FILE_CREATE -> ProtocolMessage.FileCreated(
                peerId = peerId,
                file = json.getString(KEY_FILE),
                isDirectory = json.getBoolean(KEY_IS_DIR),
                contentBase64 = if (json.has(KEY_CONTENT)) json.getString(KEY_CONTENT) else null,
            )
            T_FILE_DELETE -> ProtocolMessage.FileDeleted(
                peerId = peerId,
                file = json.getString(KEY_FILE),
            )
            T_FILE_RENAME -> ProtocolMessage.FileRenamed(
                peerId = peerId,
                from = json.getString(KEY_FROM),
                to = json.getString(KEY_TO),
            )
            T_MANIFEST_REQUEST -> ProtocolMessage.ManifestRequest(peerId)
            T_MANIFEST -> {
                val array = json.getJSONArray(KEY_ENTRIES)
                val entries = ArrayList<ManifestEntry>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    entries.add(
                        ManifestEntry(
                            path = item.getString(KEY_PATH),
                            size = item.getLong(KEY_SIZE),
                            sha256 = item.getString(KEY_HASH),
                        ),
                    )
                }
                ProtocolMessage.ProjectManifest(
                    peerId = peerId,
                    entries = entries,
                    projectName = if (json.has(KEY_PROJECT_NAME)) json.getString(KEY_PROJECT_NAME) else "",
                )
            }
            T_FILE_REQUEST -> {
                val array = json.getJSONArray(KEY_PATHS)
                val paths = ArrayList<String>(array.length())
                for (i in 0 until array.length()) paths.add(array.getString(i))
                ProtocolMessage.FileRequest(peerId, paths)
            }
            T_TRANSFER_DONE -> ProtocolMessage.FileTransferComplete(peerId)
            T_RESYNC_REQUEST -> ProtocolMessage.ResyncRequest(
                peerId = peerId,
                file = json.getString(KEY_FILE),
            )
            else -> throw IllegalArgumentException("Unknown message type: $type")
        }
    }
}
