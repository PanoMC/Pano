package com.panomc.platform.route.api.panel.platform

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class DisconnectPlatformAPI(
    private val panoApiManager: PanoApiManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/platform/disconnect", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        panoApiManager.disconnectPlatform()

        return Successful()
    }
}