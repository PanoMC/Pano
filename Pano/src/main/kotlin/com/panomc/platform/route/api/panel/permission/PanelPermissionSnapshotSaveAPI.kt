package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.error.LastAdmin
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Endpoint
class PanelPermissionSnapshotSaveAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/permission/snapshot", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePermissionGroupsPermission(), context)

        // The whole payload is read and checked before a table is touched: a payload refused halfway through
        // would otherwise take the existing permission grid with it.
        val snapshot = PermissionSnapshot.parse(context.body().asJsonObject(), permissionManager.defaultGroupName)

        val sqlClient = getSqlClient()
        val previous = PermissionSnapshot.fromRows(
            databaseManager.permissionGroupDao.getPermissionGroups(sqlClient),
            databaseManager.permissionTrackDao.getAll(sqlClient),
            databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)
        )

        val requesterIsAdmin = context.get<Boolean>("isAdmin") ?: false
        val adminsBefore = permissionManager.getUserIdsWithNode("*").toSet()

        // Only an administrator changes who is an administrator: `*`, the admin groups, membership in them and the
        // nodes of current administrators, as the per-player permission route already rules.
        if (!requesterIsAdmin) {
            val adminGroups = previous.adminGroups() + snapshot.adminGroups()
            val defaultGroup = permissionManager.defaultGroupName

            if (previous.adminKeys(adminsBefore, adminGroups, defaultGroup) != snapshot.adminKeys(adminsBefore, adminGroups, defaultGroup)) {
                throw NoPermission()
            }
        }

        // One transaction: a write the database refuses (or a crash mid-way) rolls back to the grid as it was.
        inTransaction { conn -> write(snapshot, conn) }

        permissionManager.refresh()

        // Judged on the result as well, as the per-player route does: nobody may be left without an administrator,
        // and someone who is not one may not have changed who is.
        val adminsAfter = permissionManager.getUserIdsWithNode("*").toSet()
        val refusal = when {
            adminsAfter.isEmpty() -> LastAdmin()
            !requesterIsAdmin && adminsAfter != adminsBefore -> NoPermission()
            else -> null
        }

        if (refusal != null) {
            inTransaction { conn -> write(previous, conn) }

            permissionManager.refresh()

            throw refusal
        }

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

    private suspend fun inTransaction(block: suspend (SqlClient) -> Unit) {
        val conn = (databaseManager.getSqlClient() as Pool).connection.coAwait()

        try {
            val transaction = conn.begin().coAwait()

            try {
                block(conn)

                transaction.commit().coAwait()
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { transaction.rollback().coAwait() } }

                throw e
            }
        } finally {
            withContext(NonCancellable) { runCatching { conn.close().coAwait() } }
        }
    }

    private suspend fun write(snapshot: PermissionSnapshot, sqlClient: SqlClient) {
        truncatePermissionTables(sqlClient)

        val groupIdByName = mutableMapOf<String, Long>()
        snapshot.groups.forEach { grp ->
            groupIdByName[grp.name] = databaseManager.permissionGroupDao.add(
                PermissionGroup(
                    name = grp.name,
                    displayName = grp.displayName,
                    createdAt = grp.createdAt,
                    updatedAt = grp.updatedAt
                ),
                sqlClient
            )
        }

        snapshot.tracks.forEach { track ->
            databaseManager.permissionTrackDao.add(
                PermissionTrack(
                    name = track.name,
                    description = track.description,
                    groupIds = track.groupNames.mapNotNull { groupIdByName[it] },
                    createdAt = track.createdAt,
                    updatedAt = track.updatedAt
                ),
                sqlClient
            )
        }

        snapshot.nodes.forEach { node ->
            val holderId = if (node.holderType == HolderType.GROUP) {
                groupIdByName[node.groupName] ?: return@forEach
            } else node.holderId

            databaseManager.permissionNodeDao.add(
                PermissionNode(
                    holderType = node.holderType,
                    holderId = holderId,
                    node = node.node,
                    active = node.active,
                    context = node.context,
                    expiresAt = node.expiresAt,
                    createdAt = node.createdAt,
                    updatedAt = node.updatedAt
                ),
                sqlClient
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
