package com.panomc.platform.route.api.panel.platform

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class CreatePlatformConnectCodeAPI(
    private val panoApiManager: PanoApiManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/platform/code", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val (publicKey, state) = panoApiManager.createPanoCode()

        return Successful(
            mapOf(
                "publicKey" to publicKey,
                "state" to state
            )
        )
    }
}