package com.panomc.platform.backup.remote

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * This Pano's Pano Backup rows (its full archives and snapshots) as the panel shows them: newest first and cut into
 * pages here, not in the browser. The account's other Panos are left out.
 *
 * The control plane has no paging on `GET /host/connected/backups` / `…/snapshots`, so the whole answer is read and
 * the page is sliced over the ordered rows.
 */
object RemoteBackupListing {
    private val ARCHIVE_STATUSES = setOf("DONE", "UPLOADING")
    private val SNAPSHOT_STATUSES = setOf("PENDING", "RUNNING", "DONE", "FAILED")

    /**
     * [backups] is `GET /host/connected/backups`, [snapshots] the account's snapshots (see
     * [PanoRemoteBackupService.listSnapshots]). Returns `{items[row], page, instanceId}`: a row has `type` `archive` |
     * `snapshot` and its `instanceId`.
     */
    fun build(backups: JsonObject, snapshots: JsonObject, page: PageRequest): Map<String, Any?> {
        val ownId = backups.getString("instanceId")
        val all = ArrayList<JsonObject>()

        (backups.getJsonArray("panos") ?: JsonArray()).filterIsInstance<JsonObject>()
            .filter { it.getBoolean("current") == true || (ownId != null && it.getString("instanceId") == ownId) }
            .forEach { pano ->
                val id = pano.getString("instanceId") ?: ownId ?: ""

                (pano.getJsonArray("backups") ?: JsonArray()).filterIsInstance<JsonObject>()
                    .filter { it.getString("status") in ARCHIVE_STATUSES }
                    .forEach { all.add(it.copy().put("type", "archive").put("instanceId", id)) }
            }

        (snapshots.getJsonArray("snapshots") ?: JsonArray()).filterIsInstance<JsonObject>()
            .filter { it.getString("status") in SNAPSHOT_STATUSES }
            .filter { it.getBoolean("own") == true || (ownId != null && it.getString("instanceId") == ownId) }
            .forEach { all.add(it.copy().put("type", "snapshot").put("instanceId", it.getString("instanceId") ?: ownId ?: "")) }

        all.sortByDescending { (it.getValue("createdAt") as? Number)?.toLong() ?: 0L }

        Paging.requireInRange(page, all.size.toLong())

        val items = all.drop(page.offset.toInt()).take(page.limit)

        return Paging.response(items, all.size.toLong(), page, mapOf("instanceId" to ownId))
    }
}
