package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node to turn something that already exists into a managed server (`IMPORT_SERVER`).
 *
 * The counterpart of [InstallServerMessage], and deliberately thinner: an install knows what it is
 * installing and hands the node a download URL, while an import knows only where the bytes are.
 * What the result turns out to be comes back the other way, in `IMPORT_RESULT`, because only the
 * node can look inside the directory it just assembled.
 *
 * [mode] decides which source field is filled — exactly one ever is.
 */
data class ImportServerMessage(
    val serverUuid: String,
    val taskId: String,
    /** [ImportMode] as a name; the node reads it as text. */
    val mode: String,
    /** An absolute path on the node's own host, for `FOLDER` (copied) and `IN_PLACE` (adopted). */
    val folderPath: String? = null,
    /** A transfer ticket holding the uploaded archive, for `UPLOAD`. */
    val ticket: String? = null,
    /** The `.mrpack` Pano resolved from Modrinth, for `MODPACK`. */
    val downloadUrl: String? = null,
    val filename: String? = null,
    val spec: ImportServerSpec
) : NodeMessage

/**
 * The settings an import cannot work out for itself.
 *
 * No software or version: those are what the import is trying to discover. [javaMajor] is normally
 * null for the same reason — the node picks it from the Minecraft version it detects.
 */
data class ImportServerSpec(
    val name: String,
    val memoryMb: Int,
    /**
     * 0 lets the node keep the port the imported directory was already using, or allocate one --
     * which is what an `IN_PLACE` adoption sends unless the admin typed a port, so a server whose
     * players know its address keeps it.
     */
    val port: Int,
    val javaMajor: Int?,
    val jvmArgs: List<String>,
    val acceptEula: Boolean,
    /**
     * Whether the node starts the server by itself, sent for `IN_PLACE` only (SM-74) so the node's
     * copy agrees with the row from the first moment. Null for the copy modes, which keep theirs off.
     */
    val autoStart: Boolean? = null
)

/** Where an imported server is coming from. */
enum class ImportMode {
    /** A directory already on the node's host. */
    FOLDER,

    /** A zip the browser uploaded, waiting under a transfer ticket. */
    UPLOAD,

    /** A Modrinth `.mrpack`. */
    MODPACK,

    /**
     * A directory already on the node's host, adopted where it is: nothing is copied, the node runs
     * the server from there and never deletes it (the "Pano Agent" link). Only sent to a node that
     * announced protocol 5 ([com.panomc.platform.node.NodeProtocol.supportsInPlace]).
     */
    IN_PLACE
}
