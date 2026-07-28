package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * POST rather than DELETE: the addresses travel in the body because an IPv6 address in a path
 * segment breaks path-parameter parsing, and `ApiUtil.delete` in the frozen `@panomc/sdk` cannot
 * send a body at all.
 */
@Endpoint
class PanelRemoveMaintenanceBannedIpsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/banned-ips/remove", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("ips", arraySchema().items(stringSchema()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        // The store keys addresses lowercased, the way the ban identity is derived.
        val ips = data.getJsonArray("ips")
            .mapNotNull { (it as? String)?.trim()?.lowercase()?.ifEmpty { null } }
            .distinct()

        if (ips.isEmpty()) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()
        val authUserId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        // The manager writes the MaintenanceModeUnbannedIpLog itself, but only when it knows who
        // is acting.
        val removed = maintenanceModeManager.removeBans(ips, authUserId, authUsername)

        return Successful(mapOf("removed" to removed))
    }
}
