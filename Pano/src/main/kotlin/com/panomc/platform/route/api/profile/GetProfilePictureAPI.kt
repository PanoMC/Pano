package com.panomc.platform.route.api.profile

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.ProfilePictureEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class GetProfilePictureAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/api/profile/picture/:username", RouteType.GET))

    // Panel chrome — rendered on nearly every panel screen.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    companion object {
        private const val CACHE_TTL_SECONDS = 5 * 60 // 5 minutes
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()
        val user = databaseManager.userDao.getByUsername(username, sqlClient)

        var redirectUrl = "https://minotar.net/avatar/$username"

        if (user != null) {
            // Let plugins override the profile picture URL
            val listeners = PluginEventManager.getPanoEventListeners<ProfilePictureEventListener>()

            for (listener in listeners) {
                val url = listener.resolveProfilePictureUrl(user)
                if (url != null) {
                    redirectUrl = url
                    break
                }
            }
        }

        context.response()
            .setStatusCode(302)
            .putHeader("Location", redirectUrl)
            .putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS")
            .end()

        return null
    }
}
