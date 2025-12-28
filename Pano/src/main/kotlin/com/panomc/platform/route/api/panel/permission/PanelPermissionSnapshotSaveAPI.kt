package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Endpoint
class PanelPermissionSnapshotSaveAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permission/snapshot", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePermissionGroupsPermission(), context)

        val body = context.body().asJsonObject()
        val sqlClient = getSqlClient()

        val incomingGroups = body.getJsonArray("groups") ?: JsonArray()
        val incomingTracks = body.getJsonArray("tracks") ?: JsonArray()
        val incomingNodes = body.getJsonArray("nodes") ?: JsonArray()

        // Parse groups
        val groupsFromPayload = incomingGroups.mapNotNull { el ->
            (el as? JsonObject)?.let { obj ->
                PermissionGroup(
                    id = obj.getLong("id") ?: -1,
                    name = obj.getString("name"),
                    displayName = obj.getString("displayName") ?: obj.getString("name"),
                    createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = obj.getLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
        }.toMutableList()

        // Ensure admin and default exist
        ensureGroup(groupsFromPayload, name = permissionManager.defaultGroupName, displayName = permissionManager.defaultGroupName, weight = 10)

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
            val obj = el as? JsonObject ?: return@forEach
            val groupNames = obj.getJsonArray("groupNames")?.mapNotNull { it as? String } ?: listOf<String>()
            val track = PermissionTrack(
                name = obj.getString("name"),
                description = obj.getString("description") ?: "",
                groupIds = groupNames.mapNotNull { groupIdByName[it] },
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = obj.getLong("updatedAt") ?: System.currentTimeMillis()
            )
            databaseManager.permissionTrackDao.add(track, sqlClient)
        }

        // Insert nodes (skip user->default group nodes if it's not false)
        incomingNodes.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
             val holderTypeStr = obj.getString("holderType") ?: return@forEach
             val holderType = PermissionNode.Companion.HolderType.valueOf(holderTypeStr)
            val holderId = obj.getLong("holderId") ?: return@forEach
            val nodeStr = obj.getString("node") ?: return@forEach

             if (holderType == PermissionNode.Companion.HolderType.USER && nodeStr == "group.${permissionManager.defaultGroupName}" && obj.getBoolean("active") ?: true) {
                return@forEach
            }

            val mappedHolderId = if (holderType == PermissionNode.Companion.HolderType.GROUP) {
                groupIdByName[obj.getString("holderName")] ?: groupIdByName.entries.find { it.value == holderId }?.value
            } else holderId

            if (holderType == PermissionNode.Companion.HolderType.GROUP && mappedHolderId == null) {
                return@forEach
            }

            if (obj.getLong("expiresAt") != null && obj.getLong("expiresAt") < System.currentTimeMillis()) {
                return@forEach
            }

            val permissionNode = PermissionNode(
                holderType = holderType,
                holderId = mappedHolderId ?: holderId,
                node = nodeStr,
                active = obj.getBoolean("active") ?: true,
                context = obj.getJsonObject("context") ?: JsonObject(),
                expiresAt = obj.getLong("expiresAt"),
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = obj.getLong("updatedAt") ?: System.currentTimeMillis()
            )

            databaseManager.permissionNodeDao.add(permissionNode, sqlClient)
        }

        permissionManager.refresh()

        // Broadcast to connected servers that permission snapshot has changed.
        // Only send to servers where permission integration is enabled.
        val msg = com.panomc.platform.server.message.PermissionsSnapshotUpdatedMessage()
        serverManager.getConnectedServers().keys
            .filter { it.settings.permissionIntegration }
            .forEach { srv ->
                serverManager.sendMessage(msg, srv)
            }

        return Successful()
    }

    private fun ensureGroup(list: MutableList<PermissionGroup>, name: String, displayName: String, weight: Int) {
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

