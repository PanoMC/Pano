package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelReloadThemesAPI(
    private val authProvider: AuthProvider,
    private val uiManager: UIManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        uiManager.reloadInstalledThemes()

        return Successful()
    }
}