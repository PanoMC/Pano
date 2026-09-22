package com.panomc.platform.server.backup

/**
 * Lifecycle of one backup row.
 *
 * [CREATING] exists because a backup is a row before it is a file: Pano writes it when the panel
 * asks, and only the node's `BACKUP_CREATED` turns it into something that can be downloaded or
 * restored. A row stuck on [CREATING] is a backup whose node never finished, which is exactly what
 * an operator needs to see rather than a list that silently omits it.
 */
enum class ServerBackupStatus {
    CREATING,
    READY,
    FAILED;

    companion object {
        fun fromId(id: String?): ServerBackupStatus = entries.firstOrNull { it.name == id?.uppercase() } ?: CREATING
    }
}
