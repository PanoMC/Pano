package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/** `BACKUP_CREATED`: a node finished an archive and is telling Pano what it holds. */
class BackupCreatedEventRequest(
    val serverUuid: String? = null,
    val backup: BackupData? = null
) : NodeEventRequest() {
    class BackupData(
        val id: String? = null,
        val name: String? = null,
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val createdAt: Long? = null,
        /** Absent from a node that predates backups v2, which made a full zip of everything. */
        val mode: String? = null,
        val scope: String? = null,
        val fileCount: Long? = null,
        /** Bytes this backup wrote to disk: the archive, or a snapshot's new chunks. */
        val storedBytes: Long? = null,
        val include: List<String>? = null,
        val exclude: List<String>? = null
    )
}
