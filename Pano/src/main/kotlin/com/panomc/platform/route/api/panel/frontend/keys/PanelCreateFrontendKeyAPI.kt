package com.panomc.platform.route.api.panel.frontend.keys

import com.panomc.platform.access.FrontendKeyService
import com.panomc.platform.UIManager
import com.panomc.platform.access.FrontendAccessDisabled
import org.springframework.beans.factory.ObjectProvider
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Creates a front-end key. The key is in this answer and nowhere else, together with the two
 * `.env` lines a front-end server needs (`PANO_API_URL`, `PANO_FRONTEND_KEY`).
 */
@Endpoint
class PanelCreateFrontendKeyAPI(
    private val frontendKeyService: FrontendKeyService,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    private val uiManager: ObjectProvider<UIManager>
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/keys", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("name", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        FrontendAccessDisabled.requireOff(uiManager.getObject().frontendMode)

        val name = getParameters(context).body().jsonObject.getString("name").trim()

        if (name.isEmpty() || name.length > FrontendKeyService.MAX_NAME_LENGTH) {
            throw InvalidFields(mapOf("name" to true))
        }

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val created = frontendKeyService.create(name, userId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(CreatedFrontendKeyLog(userId, username, name), sqlClient)

        return Successful(
            mapOf(
                "id" to created.id,
                "name" to created.name,
                "key" to created.key,
                "env" to listOf(
                    "PANO_API_URL=${apiUrl(context)}",
                    "PANO_FRONTEND_KEY=${created.key}"
                )
            )
        )
    }

    /** Where a front-end server reaches the API: the configured website URL, else the host this request came to. */
    private fun apiUrl(context: RoutingContext): String {
        val websiteUrl = configManager.config.websiteUrl.trim().trimEnd('/')

        if (websiteUrl.isNotEmpty()) return "$websiteUrl/api"

        val request = context.request()

        return "${request.scheme()}://${request.authority()?.toString() ?: "localhost"}/api"
    }
}
