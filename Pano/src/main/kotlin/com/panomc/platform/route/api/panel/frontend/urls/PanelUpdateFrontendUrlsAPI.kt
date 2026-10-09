package com.panomc.platform.route.api.panel.frontend.urls

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Replaces the admin's overrides of the front-end URL map (doc 05 section 10.1, step 1):
 * `{ "overrides": { "<target>": "<site path or http(s) URL>" } }`. A target left out loses its override;
 * an empty string does the same. A key that is not a known target, or a value that is neither a
 * site path nor an http(s) URL, is `INVALID_FIELDS` and nothing is saved.
 */
@Endpoint
class PanelUpdateFrontendUrlsAPI(
    private val frontendUrlMap: FrontendUrlMap,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/urls", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("overrides", objectSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val raw = getParameters(context).body().jsonObject.getJsonObject("overrides")
        val overrides = linkedMapOf<String, String>()
        val invalid = linkedMapOf<String, Any?>()

        raw.fieldNames().forEach { id ->
            when (val value = raw.getValue(id)) {
                null -> Unit

                is String -> if (value.isNotBlank()) overrides[id] = value.trim()

                else -> invalid["overrides.$id"] = "INVALID_LOCATION"
            }
        }

        frontendUrlMap.validateOverrides(overrides).forEach { (id, code) -> invalid["overrides.$id"] = code }

        if (invalid.isNotEmpty()) {
            throw InvalidFields(invalid)
        }

        val sqlClient = getSqlClient()

        frontendUrlMap.saveOverrides(overrides, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(ChangedFrontendUrlsLog(userId, username, overrides.size), sqlClient)

        return Successful(frontendUrlsState(frontendUrlMap))
    }
}
