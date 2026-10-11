package com.panomc.platform.backup.remote

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The account's Pano Backup rows (full archives and snapshots of every Pano) as the panel shows them: grouped
 * by Pano (this one first), newest first inside a group, and cut into pages here, not in the browser.
 *
 * The control plane has no paging on `GET /host/connected/backups` / `…/snapshots`, so the whole answer is read and
 * the page is sliced over the ordered rows.
 */
object RemoteBackupListing {
    private val ARCHIVE_STATUSES = setOf("DONE", "UPLOADING")
    private val SNAPSHOT_STATUSES = setOf("PENDING", "RUNNING", "DONE", "FAILED")

    private class Group(
        val instanceId: String,
        val instanceName: String,
        val current: Boolean,
        val connected: Boolean?,
        var usedBytes: Long,
        val lastBackupAt: Any?,
        val rows: MutableList<JsonObject> = ArrayList()
    )

    /**
     * [backups] is `GET /host/connected/backups`, [snapshots] the account's snapshots (see
     * [PanoRemoteBackupService.listSnapshots]). Returns `{items[row], page, instanceId, groups[{instanceId,
     * instanceName, current, connected, usedBytes, lastBackupAt, count}]}`: a row has `type` `archive` | `snapshot`
     * and its `instanceId`; `groups` are all the account's Panos (not only the page's), `count` their rows.
     */
    fun build(backups: JsonObject, snapshots: JsonObject, page: PageRequest): Map<String, Any?> {
        val ownId = backups.getString("instanceId")
        val groups = ArrayList<Group>()

        (backups.getJsonArray("panos") ?: JsonArray()).forEach { pano ->
            pano as JsonObject

            val id = pano.getString("instanceId") ?: ""
            val rows = (pano.getJsonArray("backups") ?: JsonArray())
                .filterIsInstance<JsonObject>()
                .filter { it.getString("status") in ARCHIVE_STATUSES }
                .map { it.copy().put("type", "archive").put("instanceId", id) }
            val current = pano.getBoolean("current") == true || (ownId != null && id == ownId)

            if (!current && rows.isEmpty()) return@forEach

            groups.add(
                Group(
                    id,
                    pano.getString("instanceName")?.takeIf { it.isNotEmpty() } ?: id,
                    current,
                    pano.getBoolean("connected"),
                    pano.getValue("usedBytes").let { (it as? Number)?.toLong() ?: 0L },
                    pano.getValue("lastBackupAt")
                ).also { it.rows.addAll(rows) }
            )
        }

        (snapshots.getJsonArray("snapshots") ?: JsonArray()).filterIsInstance<JsonObject>()
            .filter { it.getString("status") in SNAPSHOT_STATUSES }
            .forEach { snapshot ->
                val id = snapshot.getString("instanceId") ?: ""
                val group = groups.firstOrNull { it.instanceId == id } ?: Group(
                    id,
                    snapshot.getString("instanceName")?.takeIf { it.isNotEmpty() } ?: id,
                    snapshot.getBoolean("own") == true || (ownId != null && id == ownId),
                    null,
                    0L,
                    null
                ).also { groups.add(it) }

                group.usedBytes += maxOf(0L, (snapshot.getValue("uniqueBytes") as? Number)?.toLong() ?: 0L)
                group.rows.add(snapshot.copy().put("type", "snapshot").put("instanceId", id))
            }

        groups.forEach { group -> group.rows.sortByDescending { (it.getValue("createdAt") as? Number)?.toLong() ?: 0L } }

        // sortedByDescending is stable: this Pano first, the others keep the control plane's order.
        val ordered = groups.sortedByDescending { it.current }
        val all = ordered.flatMap { it.rows }

        Paging.requireInRange(page, all.size.toLong())

        val items = all.drop(page.offset.toInt()).take(page.limit)

        return Paging.response(
            items,
            all.size.toLong(),
            page,
            mapOf(
                "instanceId" to ownId,
                "groups" to ordered.map {
                    JsonObject()
                        .put("instanceId", it.instanceId)
                        .put("instanceName", it.instanceName)
                        .put("current", it.current)
                        .put("connected", it.connected)
                        .put("usedBytes", it.usedBytes)
                        .put("lastBackupAt", it.lastBackupAt)
                        .put("count", it.rows.size)
                }
            )
        )
    }
}
