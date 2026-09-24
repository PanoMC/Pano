package com.panomc.platform.route.api.panel.server.schedules

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerScheduleActionLog
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.ScheduleDefinitions
import com.panomc.platform.server.schedule.ServerScheduleService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/** Replaces one schedule's definition, steps included. */
@Endpoint
class PanelUpdateServerScheduleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val scheduleService: ServerScheduleService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/schedules/:sid", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("sid", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .requiredProperty("cron", stringSchema())
                        .optionalProperty("timezone", stringSchema())
                        .optionalProperty("enabled", booleanSchema())
                        .optionalProperty("warnMinutes", intSchema())
                        .requiredProperty("tasks", arraySchema().items(objectSchema()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val scheduleId = parameters.pathParameter("sid").long

        authProvider.requirePermission(ManageServerSchedulesPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val schedule = databaseManager.serverScheduleDao.getById(scheduleId, sqlClient) ?: throw NotExists()

        // The schedule id is its own namespace, so a schedule of another server must not be
        // reachable through a server the caller happens to have permission on.
        if (schedule.serverId != id) {
            throw NotExists()
        }

        val definition = ScheduleDefinitions.parse(
            parameters.body().jsonObject,
            backupsAllowed = scheduleService.backupsAllowed(server),
            defaultTimezone = java.time.ZoneId.systemDefault().id
        )

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        val updated = scheduleService.update(server, schedule, definition, sqlClient)

        databaseManager.panelActivityLogDao.add(
            ServerScheduleActionLog(
                userId,
                username,
                id,
                ServerScheduleActionLog.ACTION_UPDATE,
                updated.name,
                updated.cron
            ),
            sqlClient
        )

        return Successful(mapOf("nextRunAt" to updated.nextRunAt))
    }
}
