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
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateLocaleTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/locales/:localeId/types/:type/translations", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
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

        translations.forEach { (key, value) ->
            if (!isSafeMarkup(value.toString())) {
                throw BadRequest(
                    extras = mapOf(
                        "field" to "translations",
                        "key" to key,
                        "message" to "\"$key\" holds HTML that is not allowed in a translation."
                    )
                )
            }
        }

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
                "translations" to translations,
                "count" to translations.count()
            )
        )
    }

    companion object {
        private val TAG = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9]*)([^<>]*)>""")
        private val ATTRIBUTE = Regex("""\s*([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val MARKUP_START = Regex("""<\s*[a-zA-Z!/?]""")
        private val SAFE_HREF = Regex("""^((https?://|/|#|mailto:)[^\s<>"'`]*|\{[a-zA-Z0-9_.-]+})$""", RegexOption.IGNORE_CASE)
        private val SAFE_CLASS = Regex("""^[a-zA-Z0-9_ -]*$""")

        private val PLAIN_TAGS = setOf("b", "strong", "i", "em", "u", "s", "br", "code", "small", "span", "p", "ul", "ol", "li", "mark")
        private val LINK_ATTRIBUTES = setOf("href", "target", "rel", "class")

        /**
         * Whether [value] is plain text or uses only simple formatting tags.
         *
         * Translations are rendered as HTML in the panel and on the maintenance page, so a value
         * may not carry scripts, event handlers or anything but a few formatting tags (with at most
         * a `class`) and `<a href>` links to web, relative or mail addresses or a `{placeholder}`.
         */
        fun isSafeMarkup(value: String): Boolean {
            var rest = value

            TAG.findAll(value).forEach { match ->
                val closing = match.groupValues[1].isNotEmpty()
                val tag = match.groupValues[2].lowercase()
                val attributes = match.groupValues[3].trim().removeSuffix("/").trim()

                val allowed = when {
                    closing -> attributes.isEmpty() && (tag in PLAIN_TAGS || tag == "a")
                    tag in PLAIN_TAGS -> attributesAreSafe(attributes, setOf("class"))
                    tag == "a" -> attributesAreSafe(attributes, LINK_ATTRIBUTES)
                    else -> false
                }

                if (!allowed) {
                    return false
                }

                rest = rest.replace(match.value, "")
            }

            return !MARKUP_START.containsMatchIn(rest)
        }

        private fun attributesAreSafe(attributes: String, allowed: Set<String>): Boolean {
            val matches = ATTRIBUTE.findAll(attributes).toList()

            if (ATTRIBUTE.replace(attributes, "").isNotBlank()) {
                return false
            }

            return matches.all { match ->
                val name = match.groupValues[1].lowercase()
                val attributeValue = match.groupValues[2].ifEmpty { match.groupValues[3] }

                name in allowed && when (name) {
                    "href" -> SAFE_HREF.matches(attributeValue.trim())
                    "class" -> SAFE_CLASS.matches(attributeValue)
                    else -> attributeValue.none { it == '<' || it == '>' }
                }
            }
        }
    }
}
