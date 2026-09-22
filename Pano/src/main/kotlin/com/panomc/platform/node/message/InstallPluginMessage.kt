package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node to put one plugin or mod jar into a server (`INSTALL_PLUGIN`).
 *
 * Pano resolves the URL and passes on whichever checksum the source published, because Pano never
 * sees the bytes: the node downloads them and is the only side that can verify them. Everything
 * here is data the node re-validates — the filename above all, which is the only field that
 * becomes a path on its disk.
 *
 * [replaceFilename] is the jar this one supersedes, for the update case. The node deletes it only
 * after the replacement is safely in place.
 */
data class InstallPluginMessage(
    val serverUuid: String,
    val taskId: String,
    val downloadUrl: String,
    val filename: String,
    /** `plugins` or, for mod loaders, `mods`. */
    val targetDir: String,
    val sha512: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val replaceFilename: String? = null
) : NodeMessage
