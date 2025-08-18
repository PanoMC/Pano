package com.panomc.platform.route.api.panel.install

import com.panomc.platform.InstallManager
import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.*

@Endpoint
class PanelGetInstallResourceInfoAPI(
    private val panoApiManager: PanoApiManager,
    private val installManager: InstallManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/install/store/:versionId/info", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("versionId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val versionId = try {
            UUID.fromString(parameters.pathParameter("versionId").string)
        } catch (_: Exception) {
            throw BadRequest()
        }

        val versionInfo = panoApiManager.getVersionInfo(versionId)

        val resourceId = versionInfo.getString("resourceId")
        val type = ResourceType.valueOf(versionInfo.getString("type"))

        val installed = installManager.isInstalled(resourceId, type)

        val installedResourceInfo = if (installed) {
            installManager.getResourceInfo(resourceId, type)
        } else {
            null
        }

        return Successful(
            mapOf(
                "data" to mapOf(
                    "installed" to installedResourceInfo,
                    "version" to versionInfo.map
                )
            )
        )
    }
}