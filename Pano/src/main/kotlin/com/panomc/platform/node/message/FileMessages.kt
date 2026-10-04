package com.panomc.platform.node.message

import com.panomc.platform.node.NodeRequestMessage

/**
 * The file-manager half of the node protocol.
 *
 * Every one of these is a request: the panel is waiting on an HTTP response, so the node's answer
 * has to come back rather than arrive as a later event. Paths are always relative to the server
 * directory and are normalised and checked on both sides — Pano rejects the obvious before the
 * frame goes out, the node rejects everything again because it is the side that actually owns the
 * filesystem.
 *
 * The message name is derived from the class name (`FileListMessage` -> `FILE_LIST`), so these
 * names are the wire contract and the daemon has to move with them.
 */
class FileListMessage(
    val serverUuid: String,
    val path: String
) : NodeRequestMessage()

class FileReadMessage(
    val serverUuid: String,
    val path: String,
    val maxBytes: Int
) : NodeRequestMessage()

class FileWriteMessage(
    val serverUuid: String,
    val path: String,
    val content: String
) : NodeRequestMessage()

class FileMkdirMessage(
    val serverUuid: String,
    val path: String
) : NodeRequestMessage()

class FileDeleteMessage(
    val serverUuid: String,
    val paths: List<String>
) : NodeRequestMessage()

class FileRenameMessage(
    val serverUuid: String,
    val from: String,
    val to: String
) : NodeRequestMessage()

class FileArchiveMessage(
    val serverUuid: String,
    val paths: List<String>,
    val target: String
) : NodeRequestMessage()

class FileUnarchiveMessage(
    val serverUuid: String,
    val path: String,
    val target: String
) : NodeRequestMessage()

/**
 * Asks a node for the hashes of named jars in one directory (`FILE_HASHES`).
 *
 * The names are sent rather than "hash everything in there" so the node reads only the files Pano
 * is actually trying to identify, and so the answer's size is decided here rather than by whatever
 * somebody dropped into the directory.
 */
class FileHashesMessage(
    val serverUuid: String,
    val path: String,
    val names: List<String>
) : NodeRequestMessage()

class FileChmodMessage(
    val serverUuid: String,
    val path: String,
    val mode: String
) : NodeRequestMessage()

/**
 * Tells a node to stream one file up to Pano under [ticket].
 *
 * Fire and forget on the socket: what the browser is waiting on is the node's HTTP request
 * arriving at `PUT /api/node/transfer/<ticket>`, and a failure is reported on that same request
 * rather than over here, so there is only ever one thing to wait for.
 *
 * With [paths] set the source streams a zip instead of one file: [path] becomes the directory the
 * entry names are relative to, and every entry of [paths] is a full server-relative path inside
 * it. Left null for a single file, where Gson drops it from the frame altogether, so a node or a
 * plugin that predates archives still reads exactly the message it always did.
 */
class TransferPullMessage(
    val ticket: String,
    val serverUuid: String,
    val path: String,
    val paths: List<String>? = null
) : NodeRequestMessage()

/**
 * Tells a node to fetch an uploaded file from Pano and write it into the server.
 *
 * A request, unlike a pull: the browser's upload is finished by the time this goes out, so the
 * only way the panel can say "saved" rather than "sent" is to wait for the node to report that it
 * actually wrote the file.
 */
class TransferPushMessage(
    val ticket: String,
    val serverUuid: String,
    val path: String,
    val size: Long
) : NodeRequestMessage()
