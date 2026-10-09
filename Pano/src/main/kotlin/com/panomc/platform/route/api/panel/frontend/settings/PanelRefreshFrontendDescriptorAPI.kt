package com.panomc.platform.route.api.panel.frontend.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.frontend.FrontendDescriptor
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * "Refresh" of the front-end descriptor (doc 05 section 8.2): fetches the document at `descriptor-url` (default
 * `<upstream-url>/.well-known/pano-frontend.json`) and caches it. Answers `{ id, title, hasSettingsSchema }`, or
 * `{ id: null }` when there is nothing to fetch from (the cache is cleared). The previous cache is kept when the
 * fetch fails: `DESCRIPTOR_HOST_NOT_ALLOWED`, `FRONTEND_DESCRIPTOR_UNREACHABLE`, `FRONTEND_DESCRIPTOR_INVALID`
 * or `FRONTEND_SETTINGS_SCHEMA_INVALID`.
 */
@Endpoint
class PanelRefreshFrontendDescriptorAPI(
    private val frontendDescriptor: FrontendDescriptor,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/descriptor/refresh", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val sqlClient = getSqlClient()
        val descriptor = frontendDescriptor.refresh(sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(RefreshedFrontendDescriptorLog(userId, username, descriptor?.id), sqlClient)

        return Successful(
            mapOf(
                "id" to descriptor?.id,
                "title" to descriptor?.title,
                "hasSettingsSchema" to (descriptor?.schema != null)
            )
        )
    }
}
