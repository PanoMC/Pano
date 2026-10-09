package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedPlayerLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class UpdateProfileAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : LoggedInApi() {
    override val paths = listOf(Path("/profile", RouteType.PUT))

    override val doc = EndpointDoc(
        summary = "Changes the signed-in user's language.",
        tag = "profile",
        response = objectSchema(),
        errors = listOf(NotExists::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .optionalProperty("localeCode", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val localeCode = data.getString("localeCode")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        if (localeCode != null && localeCode != user.localeCode) {
            if (!configManager.config.allowUserLocaleSelection) {
                throw NoPermission()
            }

            val localeExists = databaseManager.localeDao.existsByCode(localeCode, sqlClient)

            if (!localeExists) {
                throw NotExists()
            }

            databaseManager.userDao.setLocaleCodeById(localeCode, userId, sqlClient)
        }

        return Successful()
    }
}