package com.panomc.platform.route.api.panel.ticket.category

import io.vertx.core.json.JsonArray
import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.ticket.TicketFields
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CreatedTicketCategoryLog
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelAddTicketCategoryAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val contentTranslations: ContentTranslations
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/category", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("description", stringSchema())
                        .optionalProperty("enabled", booleanSchema())
                        .optionalProperty("autoReplyTemplateId", numberSchema())
                        .optionalProperty("fields", arraySchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val title = data.getString("title")
        val description = data.getString("description")
        val enabled = data.getBoolean("enabled") ?: true
        val autoReplyTemplateId = data.getLong("autoReplyTemplateId") ?: -1L

        validateForm(title)

        // the custom fields, checked before anything is written
        val fieldDefinitions = try {
            TicketFields.parseDefinitions(data.getValue("fields"))
        } catch (e: TicketFields.Invalid) {
            throw InvalidFields(e.errors)
        }

        // the translations, checked before anything is written (the keys get the id once it exists)
        try {
            contentTranslations.parseCategory(-1, data.getValue("i18n"), data.getValue("fields") as? JsonArray, fieldDefinitions)
        } catch (e: TicketFields.Invalid) {
            throw InvalidFields(e.errors)
        }

        val sqlClient = getSqlClient()

        if (autoReplyTemplateId != -1L && databaseManager.ticketTemplateDao.getById(autoReplyTemplateId, sqlClient) == null) {
            throw InvalidFields(mapOf("autoReplyTemplateId" to true))
        }

        val categoryId = databaseManager.ticketCategoryDao.add(
            TicketCategory(title = title, description = description, enabled = enabled, autoReplyTemplateId = autoReplyTemplateId),
            sqlClient
        )

        val url = TextUtil.convertStringToUrl(title, 32)

        databaseManager.ticketCategoryDao.updateUrlById(categoryId, "$url-$categoryId", sqlClient)

        databaseManager.ticketCategoryFieldSetDao.set(categoryId, TicketFields.definitionsJson(fieldDefinitions).encode(), sqlClient)

        contentTranslations.replace(
            contentTranslations.categoryPrefix(categoryId),
            contentTranslations.parseCategory(categoryId, data.getValue("i18n"), data.getValue("fields") as? JsonArray, fieldDefinitions),
            sqlClient
        )

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(CreatedTicketCategoryLog(userId, username, title), sqlClient)

        return Successful(mapOf("id" to categoryId))
    }

    private fun validateForm(
        title: String,
//        description: String,
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (errors.isNotEmpty()) {
            throw InvalidFields(errors)
        }
    }
}