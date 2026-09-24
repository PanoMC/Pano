package com.panomc.platform.route.api.panel.server.schedules

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerScheduleActionLog
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.ServerScheduleService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.platform.util.UsageMode

/** Turns one schedule on or off without touching what it does. */
@Endpoint
class PanelToggleServerScheduleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val scheduleService: ServerScheduleService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/schedules/:sid/toggle", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("sid", numberSchema()))
            .body(json(objectSchema().requiredProperty("enabled", booleanSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val scheduleId = parameters.pathParameter("sid").long

        authProvider.requirePermission(ManageServerSchedulesPermission(), context, id)

        val enabled = parameters.body().jsonObject.getBoolean("enabled", true)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val schedule = databaseManager.serverScheduleDao.getById(scheduleId, sqlClient) ?: throw NotExists()

        if (schedule.serverId != id) {
            throw NotExists()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        scheduleService.setEnabled(server, schedule, enabled, sqlClient)

        databaseManager.panelActivityLogDao.add(
            ServerScheduleActionLog(
                userId,
                username,
                id,
                ServerScheduleActionLog.ACTION_TOGGLE,
                schedule.name,
                schedule.cron
            ),
            sqlClient
        )

        return Successful(mapOf("enabled" to enabled, "nextRunAt" to schedule.nextRunAt))
    }
}
