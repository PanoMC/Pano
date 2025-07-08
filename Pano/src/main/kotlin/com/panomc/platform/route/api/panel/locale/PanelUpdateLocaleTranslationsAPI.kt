package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedTranslationsLog
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateLocaleTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales/:localeId/types/:type/translations", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("localeId", numberSchema()))
            .pathParameter(param("type", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("translations", objectSchema().additionalProperties(stringSchema()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTranslations(), context)

        val parameters = getParameters(context)
        val requestBody = parameters.body().jsonObject

        val translations = requestBody.getJsonObject("translations").map

        val localeId = parameters.pathParameter("localeId").long
        val type = try {
            TranslationType.valueOf(parameters.pathParameter("type").string)
        } catch (e: Exception) {
            throw BadRequest("Invalid type")
        }

        val sqlClient = getSqlClient()

        val locale = databaseManager.localeDao.byId(localeId, sqlClient) ?: throw NotFound()

        val customTranslations = databaseManager.translationDao.getByLocaleIdAndType(localeId, type, sqlClient)
        val customTranslationKeyMap = customTranslations.associateBy { it.key }

        val updateList = mutableListOf<Translation>()
        val addList = mutableListOf<Translation>()
        val removeList = mutableListOf<Translation>()

        translations.forEach {
            val customTranslation = customTranslationKeyMap[it.key]
            val value = it.value.toString()

            if (customTranslation != null) {
                customTranslation.value = value

                updateList.add(customTranslation)
            } else {
                val newCustomTranslation = Translation(
                    localeId = localeId,
                    type = type,
                    key = it.key,
                    value = value
                )

                addList.add(newCustomTranslation)
            }
        }

        customTranslationKeyMap.forEach {
            if (translations[it.key] == null) {
                removeList.add(it.value)
            }
        }

        databaseManager.translationDao.addAll(addList, sqlClient)
        databaseManager.translationDao.removeAll(removeList, sqlClient)
        databaseManager.translationDao.updateAll(updateList, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedTranslationsLog(
                userId,
                username,
                localeId,
                locale.name
            ), sqlClient
        )

        return Successful(
            mutableMapOf(
                "data" to translations,
                "meta" to mapOf(
                    "totalCount" to translations.count(),
                )
            )
        )
    }
}