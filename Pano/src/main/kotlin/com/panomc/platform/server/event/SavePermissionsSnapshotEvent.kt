package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.User
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.event.request.SavePermissionsSnapshotEventRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Accepts a full permissions snapshot from a connected server and writes it to DB.
 *
 * IMPORTANT: This will reset permission_group/permission_track/permission_node tables.
 *
 * Pano user rows are auto-created when the snapshot references a USER holder by
 * `holderName` (Minecraft username) that does not yet exist on the platform. This
 * mirrors LuckPerms-only users into Pano so they show up on the panel with their
 * permissions intact.
 */
@Event
class SavePermissionsSnapshotEvent(
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager
) : ServerEvent<SavePermissionsSnapshotEventRequest, ServerEventResponse>() {

    override suspend fun handle(request: SavePermissionsSnapshotEventRequest, server: Server): ServerEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val body = try {
            JsonObject(request.snapshot)
        } catch (_: Exception) {
            return null
        }

        fun toJsonArray(key: String): JsonArray {
            val direct = body.getJsonArray(key)
            if (direct != null) return direct
            val raw = body.getValue(key)
            return when (raw) {
                is JsonArray -> raw
                is List<*> -> JsonArray(raw)
                else -> JsonArray()
            }
        }

        val incomingGroups = toJsonArray("groups")
        val incomingTracks = toJsonArray("tracks")
        val incomingNodes = toJsonArray("nodes")

        // Parse groups
        val groupsFromPayload = incomingGroups.mapNotNull { el ->
            val obj = when (el) {
                is JsonObject -> el
                is Map<*, *> -> JsonObject.mapFrom(el)
                else -> null
            } ?: return@mapNotNull null
            obj.let {
                val name = it.getString("name") ?: return@let null
                PermissionGroup(
                    id = it.getLong("id") ?: -1,
                    name = name,
                    displayName = it.getString("displayName") ?: name,
                    createdAt = it.getLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = it.getLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
        }.toMutableList()

        // Ensure default group exists
        ensureGroup(
            list = groupsFromPayload,
            name = permissionManager.defaultGroupName,
            displayName = permissionManager.defaultGroupName
        )

        // Resolve/create Pano user rows for any USER-holder nodes with a username before we
        // touch the permission tables. This guarantees that LP-only users (without a Pano
        // account) end up registered on the platform with their permissions saved.
        val userIdByUsername = resolveUserIdsForSnapshot(incomingNodes, sqlClient)

        // Reset tables
        truncatePermissionTables(sqlClient)

        // Insert groups and keep name->id map
        val groupIdByName = mutableMapOf<String, Long>()
        groupsFromPayload.forEach { grp ->
            val newId = databaseManager.permissionGroupDao.add(grp, sqlClient)
            groupIdByName[grp.name] = newId
        }

        // Insert tracks
        incomingTracks.forEach { el ->
            val obj = when (el) {
                is JsonObject -> el
                is Map<*, *> -> JsonObject.mapFrom(el)
                else -> null
            } ?: return@forEach
            val name = obj.getString("name") ?: return@forEach
            val groupNames = (obj.getJsonArray("groupNames")
                ?: (obj.getValue("groupNames") as? List<*>)?.let { JsonArray(it) }
                ?: JsonArray()
                ).mapNotNull { it as? String }
            val track = PermissionTrack(
                name = name,
                description = obj.getString("description") ?: "",
                groupIds = groupNames.mapNotNull { groupIdByName[it] },
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = obj.getLong("updatedAt") ?: System.currentTimeMillis()
            )
            databaseManager.permissionTrackDao.add(track, sqlClient)
        }

        // Insert nodes
        incomingNodes.forEach { el ->
            val obj = when (el) {
                is JsonObject -> el
                is Map<*, *> -> JsonObject.mapFrom(el)
                else -> null
            } ?: return@forEach
            val holderTypeStr = obj.getString("holderType") ?: return@forEach
            val holderType = try {
                HolderType.valueOf(holderTypeStr)
            } catch (_: Exception) {
                return@forEach
            }

            val nodeStr = obj.getString("node") ?: return@forEach

            // Skip explicit user->default group nodes (LP already implies default group in platform logic)
            if (holderType == HolderType.USER &&
                nodeStr == "group.${permissionManager.defaultGroupName}" &&
                (obj.getBoolean("active") ?: true)
            ) {
                return@forEach
            }

            val expiresAt = obj.getLong("expiresAt")
            if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
                return@forEach
            }

            val mappedHolderId: Long = when (holderType) {
                HolderType.GROUP -> {
                    val groupName = obj.getString("holderName") ?: return@forEach
                    groupIdByName[groupName] ?: return@forEach
                }

                HolderType.USER -> {
                    // Prefer resolving by username (we just made sure every referenced username
                    // has a Pano user). Fall back to explicit holderId when it's a valid id.
                    val nameKey = obj.getString("holderName")
                    val byName = nameKey?.let { userIdByUsername[it] }
                    val explicitId = obj.getLong("holderId")?.takeIf { it > 0 }
                    byName ?: explicitId ?: return@forEach
                }
            }

            val permissionNode = PermissionNode(
                holderType = holderType,
                holderId = mappedHolderId,
                node = nodeStr,
                active = obj.getBoolean("active") ?: true,
                context = (obj.getJsonObject("context")
                    ?: (obj.getValue("context") as? Map<*, *>)?.let { JsonObject.mapFrom(it) }
                    ?: JsonObject()
                    ),
                expiresAt = expiresAt,
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = obj.getLong("updatedAt") ?: System.currentTimeMillis()
            )

            databaseManager.permissionNodeDao.add(permissionNode, sqlClient)
        }

        permissionManager.refresh()

        return null
    }

    /**
     * Build a username -> user-id map for every USER-holder node in the snapshot.
     *
     * If a referenced username does not yet have a Pano user row we create one on the fly,
     * using the Minecraft UUID from the payload (when provided) so that the MC account ends up
     * linked correctly on first sync.
     */
    private suspend fun resolveUserIdsForSnapshot(
        incomingNodes: JsonArray,
        sqlClient: SqlClient
    ): Map<String, Long> {
        val usernames = mutableSetOf<String>()
        val uuidByUsername = mutableMapOf<String, String>()

        incomingNodes.forEach { el ->
            val obj = when (el) {
                is JsonObject -> el
                is Map<*, *> -> JsonObject.mapFrom(el)
                else -> null
            } ?: return@forEach
            if (obj.getString("holderType") != "USER") return@forEach
            val username = obj.getString("holderName")?.takeIf { it.isNotBlank() } ?: return@forEach
            usernames.add(username)
            obj.getString("holderUniqueId")?.takeIf { it.isNotBlank() }?.let { uuid ->
                uuidByUsername.putIfAbsent(username, uuid)
            }
        }

        if (usernames.isEmpty()) return emptyMap()

        val resolved = databaseManager.userDao
            .getIdsByListOfUsername(usernames.toList(), sqlClient)
            .toMutableMap()

        val now = System.currentTimeMillis()
        usernames.forEach { username ->
            if (resolved.containsKey(username)) return@forEach

            val newUser = User(
                username = username,
                email = null,
                registeredIp = "",
                registerDate = now,
                lastLoginDate = now,
                mcUuid = uuidByUsername[username]
            )

            val newId = databaseManager.userDao.add(newUser, null, sqlClient, false)
            resolved[username] = newId
        }

        return resolved
    }

    private fun ensureGroup(list: MutableList<PermissionGroup>, name: String, displayName: String) {
        if (list.none { it.name == name }) {
            list.add(
                PermissionGroup(
                    name = name,
                    displayName = displayName,
                )
            )
        }
    }

    private suspend fun truncatePermissionTables(sqlClient: SqlClient) {
        val prefix = databaseManager.getTablePrefix()
        val tables = listOf(
            "permission_node",
            "permission_track",
            "permission_group"
        )

        tables.forEach { tbl ->
            sqlClient.query("DELETE FROM `${prefix}$tbl`").execute().coAwait()
        }
    }
}


