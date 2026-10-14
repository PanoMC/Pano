package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext

/** The shared layout in use (`body`), the bundled one (`default`) and whether it is overridden. */
@Endpoint
class PanelGetMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layout", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val layout = support.service.layout(getSqlClient())

        return Successful(
            mapOf(
                "body" to layout.body,
                "default" to support.service.defaultLayout(),
                "customized" to layout.customized,
                // What the panel fills the layout preview with.
                "websiteLogo" to support.previewLogo(),
                "websiteName" to support.websiteName
            )
        )
    }
}
