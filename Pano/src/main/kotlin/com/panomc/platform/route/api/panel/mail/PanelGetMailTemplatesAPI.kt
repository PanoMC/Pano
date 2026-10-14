package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailTemplate
import com.panomc.platform.mail.template.MailTemplateIndex
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext

/** Every mail of core and the started plugins, their variants and variables, and which languages are customised. */
@Endpoint
class PanelGetMailTemplatesAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val locale = support.adminLocale(userId, sqlClient)

        val keys = databaseManager.mailTemplateDao.getAllKeys(sqlClient)
        val customized = keys.groupBy({ Triple(it.owner, it.mailKey, it.variant) }, { it.localeCode })

        val layoutCustomized = keys.any {
            it.owner == MailTemplate.LAYOUT_OWNER && it.mailKey == MailTemplate.LAYOUT_KEY
        }

        val items = support.registry.owners().flatMap { owner ->
            owner.index.mails.map { mail ->
                mapOf(
                    "owner" to owner.id,
                    "pluginId" to owner.pluginId,
                    "pluginName" to owner.pluginName,
                    "key" to mail.key,
                    "name" to MailTemplateIndex.localized(mail.name, locale, mail.key),
                    "description" to MailTemplateIndex.localized(mail.description, locale, ""),
                    "variants" to mail.variants.map { variant ->
                        mapOf(
                            "key" to variant.key,
                            "name" to MailTemplateIndex.localized(variant.name, locale, variant.key),
                            "variables" to variant.variables.map { it.toMap(locale) },
                            "customizedLocales" to customized[Triple(owner.id, mail.key, variant.key)].orEmpty().sorted()
                        )
                    }
                )
            }
        }

        val locales = databaseManager.localeDao.getAllEnabled(sqlClient).map { mapOf("code" to it.code, "name" to it.name) }

        return Successful(
            mapOf(
                "items" to items,
                "locales" to locales,
                "layoutCustomized" to layoutCustomized
            )
        )
    }
}
