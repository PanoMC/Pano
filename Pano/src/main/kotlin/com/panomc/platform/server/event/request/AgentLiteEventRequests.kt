package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

/**
 * The frames an agent-lite plugin pushes while it does a node's work (SM-47, §2.4.17 C).
 *
 * Each one is the node's frame minus its `serverUuid`: a message arriving down a plugin socket is
 * already about that server, and being told which one would only give it something to disagree
 * with. Everything is nullable for the usual reason — the plugin may be older or newer than this
 * Pano, and a missing field has to degrade rather than throw while decoding.
 */

/** `TASK_PROGRESS`: one frame of how a long job inside the server is going. */
data class ServerTaskProgressEventRequest(
    val taskId: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val percent: Int? = null,
    val message: String? = null,
    val error: String? = null,
    /** Set on the terminal frame of an install: the jar is in place but only loads on a restart. */
    val restartRequired: Boolean? = null
) : ServerEventRequest()

/** `BACKUP_CREATED`: an archive the plugin finished, so Pano can fill in the row it lists. */
class ServerBackupCreatedEventRequest(
    val backup: BackupData? = null
) : ServerEventRequest() {
    class BackupData(
        val id: String? = null,
        val name: String? = null,
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val createdAt: Long? = null,
        /** Absent from a plugin that predates backups v2, which made a full zip of everything. */
        val mode: String? = null,
        val scope: String? = null,
        val fileCount: Long? = null,
        /** Bytes this backup wrote to disk: the archive, or a snapshot's new chunks. */
        val storedBytes: Long? = null,
        val include: List<String>? = null,
        val exclude: List<String>? = null
    )
}

/**
 * `BACKUP_RESTORED`: how the restore armed for this start actually went.
 *
 * Sent right after `ON_SERVER_CONNECT`, because that is the first moment there is anywhere to send
 * it — the restore itself happened before the server had loaded a world, let alone opened a
 * socket. [taskId] is the one Pano left in `PENDING_RESTART`, and this is what finishes it.
 */
data class BackupRestoredEventRequest(
    val backupId: String? = null,
    val taskId: String? = null,
    val ok: Boolean? = null,
    val error: String? = null
) : ServerEventRequest()

/**
 * `SCHEDULE_RUN`: how one scheduled run went inside the server.
 *
 * The plugin names the schedule `scheduleId` where the node names it `scheduleUuid`; both are the
 * same value and both are accepted, because one of the two would otherwise be a silently dropped
 * run report.
 */
data class ServerScheduleRunEventRequest(
    val scheduleId: String? = null,
    val scheduleUuid: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val ok: Boolean? = null,
    val error: String? = null
) : ServerEventRequest() {
    val schedule get() = scheduleId ?: scheduleUuid
}
