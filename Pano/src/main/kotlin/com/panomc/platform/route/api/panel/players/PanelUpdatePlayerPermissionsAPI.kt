package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.PermissionNodeContext
import com.panomc.platform.auth.panel.log.UpdatedPlayerPermissionGroupLog
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.LastAdmin
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.message.PermissionsSnapshotUpdatedMessage
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.sqlclient.SqlClient

/**
 * Replaces the permission nodes of ONE user: the groups the user is in (`group.<name>`) and the user's own nodes,
 * in the LuckPerms node model the permission editor uses. The editor's own save
 * (`POST /permission/snapshot`) rewrites every table from the snapshot the browser holds; this one touches nothing
 * but the rows of this user, so the "authorize" dialog of the players pages cannot undo somebody else's edit.
 */
@Endpoint
class PanelUpdatePlayerPermissionsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/players/:id/permissions", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema().requiredProperty(
                        "nodes",
                        arraySchema().items(
                            objectSchema()
                                .requiredProperty("node", stringSchema())
                                .optionalProperty("active", booleanSchema())
                        )
                    )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePermissionGroupsPermission(), context)

        val parameters = getParameters(context)
        val playerId = parameters.pathParameter("id").long
        val incoming = parameters.body().jsonObject.getJsonArray("nodes")

        val sqlClient = getSqlClient()

        val player = databaseManager.userDao.getById(playerId, sqlClient) ?: throw NotExists()

        val requesterIsAdmin = context.get<Boolean>("isAdmin") ?: false

        // Only an administrator changes what an administrator may do.
        if (authProvider.isUserAdmin(playerId) && !requesterIsAdmin) {
            throw NoPermission()
        }

        val now = System.currentTimeMillis()
        val seen = mutableSetOf<String>()

        // Everything is checked before a row is removed: a payload refused halfway would cost the user their nodes.
        val newNodes = incoming.mapNotNull { element ->
            val obj = element as? JsonObject ?: throw InvalidData()
            val node = obj.getString("node").trim()
            val active = obj.getBoolean("active") ?: true
            val nodeContext = PermissionNodeContext.sanitize(obj.getValue("context")) ?: throw InvalidData()
            val expiresAt = obj.getLong("expiresAt")

            if (node.isEmpty() || node.length > MAX_NODE_LENGTH || node.any { it.isWhitespace() }) {
                throw InvalidData()
            }

            // Everybody is in the default group without a node saying so, as in the permission editor's save.
            if (node == "group.${permissionManager.defaultGroupName}" && active) return@mapNotNull null

            if (expiresAt != null && expiresAt < now) return@mapNotNull null

            if (!seen.add("$node|${nodeContext.encode()}")) return@mapNotNull null

            PermissionNode(
                holderType = HolderType.USER,
                holderId = playerId,
                node = node,
                active = active,
                context = nodeContext,
                expiresAt = expiresAt,
                createdAt = obj.getLong("createdAt") ?: now,
                updatedAt = now
            )
        }

        if (newNodes.size > MAX_NODES) {
            throw InvalidData()
        }

        val oldNodes = databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)
            .filter { it.holderType == HolderType.USER && it.holderId == playerId }

        replaceNodes(playerId, newNodes, sqlClient)

        // Judged on the result, not on the node names: a group can carry `*`, and so can a node of the user.
        val refusal = when {
            permissionManager.getUserIdsWithNode("*").isEmpty() -> LastAdmin()
            authProvider.isUserAdmin(playerId) && !requesterIsAdmin -> NoPermission()
            else -> null
        }

        if (refusal != null) {
            replaceNodes(playerId, oldNodes, sqlClient)

            throw refusal
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        val groups = newNodes.filter { it.active && it.node.startsWith("group.") }.map { it.node.removePrefix("group.") }

        databaseManager.panelActivityLogDao.add(
            UpdatedPlayerPermissionGroupLog(
                userId,
                username,
                player.username,
                groups.joinToString(", ").ifEmpty { permissionManager.defaultGroupName }
            ),
            sqlClient
        )

        // The servers that mirror Pano's permissions fetch the new snapshot.
        val message = PermissionsSnapshotUpdatedMessage()

        serverManager.getConnectedServers().keys
            .filter { it.settings.permissionIntegration }
            .forEach { server -> serverManager.sendMessage(message, server) }

        return Successful()
    }

    private suspend fun replaceNodes(playerId: Long, nodes: List<PermissionNode>, sqlClient: SqlClient) {
        databaseManager.permissionNodeDao.deleteByUserId(playerId, sqlClient)

        nodes.forEach { databaseManager.permissionNodeDao.add(it, sqlClient) }

        permissionManager.refresh()
    }

    companion object {
        /** No user needs more nodes of their own than this, and an unbounded list is a free write. */
        const val MAX_NODES = 500
        const val MAX_NODE_LENGTH = 200
    }
}
