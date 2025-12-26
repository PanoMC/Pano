package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelPermissionSnapshotGetAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permission/snapshot", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePermissionGroupsPermission(), context)

        val sqlClient = getSqlClient()

        val groups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val tracks = databaseManager.permissionTrackDao.getAll(sqlClient)
        val nodes = databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)

        val userIds = permissionManager.getCachedUserIds()
        val usernameMap = databaseManager.userDao.getUsernameByListOfId(userIds.toList(), sqlClient)

        val users = userIds.map { userId ->
            val userNodes = nodes.filter { it.holderType == com.panomc.platform.db.model.PermissionNode.Companion.HolderType.USER && it.holderId == userId }
            JsonObject.mapFrom(
                mapOf(
                    "id" to userId,
                    "username" to usernameMap[userId],
                    "nodes" to userNodes
                )
            )
        }

        val groupNameById = groups.associateBy({ it.id }, { it.name })

        val nodesForResponse = nodes.filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }.map { node ->
            JsonObject.mapFrom(
                mapOf(
                    "id" to node.id,
                    "holderType" to node.holderType.name,
                    "holderId" to node.holderId,
                    "holderName" to if (node.holderType == com.panomc.platform.db.model.PermissionNode.Companion.HolderType.GROUP) groupNameById[node.holderId] else null,
                    "node" to node.node,
                    "active" to node.active,
                    "context" to node.context,
                    "expiresAt" to node.expiresAt,
                    "createdAt" to node.createdAt,
                    "updatedAt" to node.updatedAt
                )
            )
        }

        val tracksResponse = tracks.map { track ->
            JsonObject.mapFrom(
                mapOf(
                    "id" to track.id,
                    "name" to track.name,
                    "description" to track.description,
                    "groupNames" to track.groupIds.mapNotNull { groupNameById[it] },
                    "groupIds" to track.groupIds,
                    "createdAt" to track.createdAt,
                    "updatedAt" to track.updatedAt
                )
            )
        }

        val result = mapOf(
            "generatedAt" to System.currentTimeMillis(),
            "groups" to groups,
            "tracks" to tracksResponse,
            "nodes" to nodesForResponse,
            "users" to users
        )

        return Successful(result)
    }
}

