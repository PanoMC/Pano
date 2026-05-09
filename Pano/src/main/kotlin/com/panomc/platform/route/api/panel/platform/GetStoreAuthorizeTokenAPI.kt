package com.panomc.platform.route.api.panel.platform

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetStoreAuthorizeTokenAPI(
    private val panoApiManager: PanoApiManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/platform/store/authorize/token", RouteType.GET))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        panoApiManager.updatePlatformMetadata()

        val (token, state) = panoApiManager.getStoreAuthorizeToken()

        return Successful(
            mapOf(
                "data" to mapOf(
                    "token" to token,
                    "state" to state
                )
            )
        )
    }
}