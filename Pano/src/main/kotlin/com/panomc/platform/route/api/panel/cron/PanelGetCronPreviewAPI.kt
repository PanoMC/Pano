package com.panomc.platform.route.api.panel.cron

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.CronSchedules
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Turns a cron expression into the next few times it would fire.
 *
 * The one control in this feature that answers "did I write what I meant?" before anything is
 * saved. Not scoped to a server because a cron expression is not about one: the permission is the
 * schedule permission generally, which is what anyone able to open the editor already has.
 *
 * An invalid expression is a successful response with `valid: false`, not an error: somebody is
 * typing, and every keystroke on the way to `0 4 * * 0` is an incomplete expression.
 *
 * The optional `serverId` exists because per-server permissions are real: somebody granted
 * `MANAGE_SERVER_SCHEDULES` only on server 5 holds no global node, and without a server to check
 * against, the preview for the schedule they are allowed to edit would be refused.
 */
@Endpoint
class PanelGetCronPreviewAPI(
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/cron/preview", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(param("cron", stringSchema()))
            .queryParameter(optionalParam("timezone", stringSchema()))
            .queryParameter(optionalParam("serverId", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val serverId = parameters.queryParameter("serverId")?.long

        if (serverId == null) {
            authProvider.requirePermission(ManageServerSchedulesPermission(), context)
        } else {
            authProvider.requirePermission(ManageServerSchedulesPermission(), context, serverId)
        }

        val cron = parameters.queryParameter("cron")?.string.orEmpty()
        val timezone = parameters.queryParameter("timezone")?.string

        val valid = CronSchedules.isValid(cron)
        val zoneValid = timezone == null || CronSchedules.isValidZone(timezone)

        if (!valid || !zoneValid) {
            return Successful(
                mapOf(
                    "valid" to false,
                    "timezoneValid" to zoneValid,
                    "description" to null,
                    "next" to JsonArray()
                )
            )
        }

        return Successful(
            mapOf(
                "valid" to true,
                "timezoneValid" to true,
                "description" to CronSchedules.describe(cron),
                "timezone" to CronSchedules.zoneOf(timezone).id,
                "next" to JsonArray(CronSchedules.nextRuns(cron, timezone, PREVIEW_COUNT))
            )
        )
    }

    companion object {
        private const val PREVIEW_COUNT = 5
    }
}
