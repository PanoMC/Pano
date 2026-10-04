package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A managed server Pano deleted without its node's help, whose files the node still has to remove
 * (SM-64, §2.4.29 A).
 *
 * Written when a server is force-deleted: the row is gone at once, and the node may have been
 * offline (or failed the delete) and so never removed the directory and backups. On that node's
 * next hello Pano sends `DELETE_SERVER` for every uuid here that the node still reports, and drops
 * the entry once the node stops reporting it.
 *
 * A list of what Pano *deleted* rather than "whatever the node has that Pano has no row for": the
 * second rule would also wipe a server whose row went missing any other way — a database restored
 * from an older backup, say — and that is a world, not a leftover.
 */
data class NodePendingDeletion(
    val id: Long = -1,
    val nodeId: Long,
    val serverUuid: String,
    val createdAt: Long = System.currentTimeMillis()
) : DBEntity()
