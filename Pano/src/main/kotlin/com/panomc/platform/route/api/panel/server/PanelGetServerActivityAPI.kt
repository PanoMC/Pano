package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.implementation.PanelActivityLogDaoImpl
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerActivityLogTypes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * One server's history (`GET /api/panel/servers/:id/activity?limit=&before=`, §2.4.12).
 *
 * A filtered view of the platform-wide activity log rather than a table of its own: every action
 * that touches a server already writes an audit entry there, and a second store would be a second
 * thing to keep correct and a second thing to forget.
 *
 * Scoped like everything else under `/servers/:id`, so a user who may manage exactly one server
 * sees exactly that server's history — and, because the log carries a `username` written at the
 * time, an entry still names the person who caused it after their account is gone.
 *
 * Everything in `details` was typed by a person. It is returned as data and never as markup; the
 * panel renders it as text (§2.7).
 */
@Endpoint
class PanelGetServerActivityAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/activity", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("limit", numberSchema()))
            // A string, not a number: the panel sends back whatever it saw as the entry's `id`,
            // and an empty value has to be accepted rather than fail validation.
            .queryParameter(optionalParam("before", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServersPermission(), context, id)

        val limit = (parameters.queryParameter("limit")?.integer ?: DEFAULT_LIMIT)
            .coerceIn(1, PanelActivityLogDaoImpl.MAX_PAGE_SIZE)

        val before = parameters.queryParameter("before")?.string?.trim()?.toLongOrNull()

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val entries = databaseManager.panelActivityLogDao.byServerId(
            serverId = id,
            types = ServerActivityLogTypes.ALL,
            limit = limit,
            beforeId = before,
            sqlClient = sqlClient
        )

        // Resolved once per distinct user rather than per entry: a page is usually one or two
        // people, and the name on the entry is only looked up for rows old enough to predate
        // logs carrying their own `username`.
        val usernames = mutableMapOf<Long, String>()

        val rows = entries.map { entry ->
            val details = entry.details.copy()
            val recorded = details.getString("username")?.takeIf { it.isNotBlank() }

            val username = recorded ?: entry.userId?.let { userId ->
                usernames.getOrPut(userId) {
                    databaseManager.userDao.getUsernameFromUserId(userId, sqlClient).orEmpty()
                }
            }.orEmpty()

            // Already reported as its own field, and repeating it inside `details` only makes the
            // panel's one-line rendering of them noisier.
            details.remove("username")
            details.remove("serverId")

            mapOf(
                "id" to entry.id,
                "type" to entry.type,
                "userId" to entry.userId,
                "username" to username,
                "createdAt" to entry.createdAt,
                "details" to details
            )
        }

        return Successful(
            mapOf(
                "entries" to rows,
                "hasMore" to (rows.size >= limit)
            )
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
