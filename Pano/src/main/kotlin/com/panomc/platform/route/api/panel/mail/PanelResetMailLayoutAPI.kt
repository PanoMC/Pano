package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ResetMailLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailTemplate
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext

/** Removes the layout override: every mail is wrapped in the bundled layout again. */
@Endpoint
class PanelResetMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layout", RouteType.DELETE))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val sqlClient = getSqlClient()

        val deleted = databaseManager.mailTemplateDao.delete(
            MailTemplate.LAYOUT_OWNER, MailTemplate.LAYOUT_KEY, MailTemplate.LAYOUT_VARIANT, MailTemplate.LAYOUT_LOCALE, sqlClient
        )

        support.service.evictLayout()

        if (deleted) {
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(ResetMailLayoutLog(userId, username), sqlClient)
        }

        return Successful(mapOf("body" to support.service.defaultLayout(), "customized" to false))
    }
}
