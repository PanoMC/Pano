package com.panomc.platform.route.api

import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class GetRegisterAgreementAPI(
    private val configManager: ConfigManager,
    private val contentTranslations: ContentTranslations
) : Api() {
    override val paths = listOf(Path("/register-agreement", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The text a new user must accept when registering.",
        tag = "site",
        response = objectSchema().requiredProperty("registerAgreement", stringSchema()),
        errors = listOf(NotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val text = configManager.config.registerAgreement
        if (text.isBlank()) {
            throw NotFound()
        }
        // the agreement in the other languages: the page shows the visitor's, else the default
        return Successful(
            mapOf("registerAgreement" to text, "registerAgreementI18n" to contentTranslations.registerAgreement(getSqlClient()))
        )
    }
}
