package com.panomc.platform.route.api.panel.frontend.origins

import com.panomc.platform.access.OriginPolicy
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Replaces the whole list of allowed origins (max [OriginPolicy.MAX_ORIGINS]). Every entry is
 * `scheme://host[:port]` on the site's registrable domain (`ORIGIN_DIFFERENT_SITE` otherwise: a different
 * domain uses a front-end key). Nothing is saved when one entry is refused.
 */
@Endpoint
class PanelUpdateFrontendOriginsAPI(
    private val originPolicy: OriginPolicy,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/origins", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("origins", arraySchema().items(stringSchema()))))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val requested = getParameters(context).body().jsonObject.getJsonArray("origins").map { it as String }

        val sqlClient = getSqlClient()

        // Without a website-url the site is the host this request came to.
        val saved = originPolicy.replace(requested, context.request().authority()?.host(), sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedFrontendOriginsLog(userId, username, saved.size), sqlClient)

        return Successful(mapOf("origins" to saved))
    }
}
