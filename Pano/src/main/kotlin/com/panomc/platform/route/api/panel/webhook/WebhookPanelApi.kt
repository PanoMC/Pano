package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.Permission
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.webhook.WebhookEndpointService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Base of the webhook panel routes (doc 06 section 4.5). Every route needs [ManagePlatformSettingsPermission]; the
 * answers carry `Cache-Control: no-store` because they can contain a freshly generated secret.
 *
 * [handle] checks the permission and calls [execute]; the work itself is in [WebhookEndpointService].
 */
abstract class WebhookPanelApi : PanelApi() {
    private val authProvider by lazy { applicationContext.getBean(AuthProvider::class.java) }

    private val actor: WebhookActor get() = actorOverride ?: defaultActor

    protected val service: WebhookEndpointService by lazy { WebhookEndpointService.current() }

    /** The permission every webhook route demands. */
    internal open fun requiredPermission(): Permission = ManagePlatformSettingsPermission()

    internal abstract suspend fun execute(context: RoutingContext): Result

    final override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(requiredPermission(), context)

        context.response().putHeader("Cache-Control", "no-store")

        return execute(context)
    }

    /** Writes one panel activity log row for the signed-in user. */
    protected suspend fun record(context: RoutingContext, build: (userId: Long, username: String) -> PanelActivityLog) =
        actor.record(context, build)

    /** The user who is acting, for per-user limits. */
    protected suspend fun userIdOf(context: RoutingContext): Long = actor.userId(context)

    /** The `name` field of the request body (the create call has no id to look the saved name up by). */
    protected fun nameOfBody(context: RoutingContext): String =
        getParameters(context).body().jsonObject?.getString("name")?.trim().orEmpty()

    /** The `:id` path parameter. */
    protected fun idOf(context: RoutingContext): Long = getParameters(context).pathParameter("id").long

    protected fun idValidation(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    /** A JSON object body whose fields the service checks one by one (`INVALID_FIELDS` names the field). */
    protected fun bodyValidation(schemaRepository: SchemaRepository, withId: Boolean): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        if (withId) builder = builder.pathParameter(param("id", numberSchema()))

        return builder
            .body(json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()
    }

    internal companion object {
        /** Tests replace the signed-in user and the activity log. */
        @Volatile
        internal var actorOverride: WebhookActor? = null

        private val defaultActor = object : WebhookActor {
            override suspend fun userId(context: RoutingContext): Long =
                applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)

            override suspend fun record(context: RoutingContext, build: (userId: Long, username: String) -> PanelActivityLog) {
                val databaseManager = applicationContext.getBean(DatabaseManager::class.java)
                val sqlClient = databaseManager.getSqlClient()
                val userId = userId(context)
                val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: return

                databaseManager.panelActivityLogDao.add(build(userId, username), sqlClient)
            }
        }
    }
}

/** Who is acting and where the activity log goes; the default reads the beans, a test gives its own. */
internal interface WebhookActor {
    suspend fun userId(context: RoutingContext): Long

    suspend fun record(context: RoutingContext, build: (userId: Long, username: String) -> PanelActivityLog)
}
