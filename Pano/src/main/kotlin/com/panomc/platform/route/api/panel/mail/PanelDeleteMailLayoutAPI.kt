package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DeletedMailLayoutLog
import com.panomc.platform.auth.panel.log.ResetMailLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.template.MailTemplateIndex
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * A custom layout is deleted and every mail that chose it is switched to `core:default`:
 * `{ deleted: true, movedMails: [ { owner, key, name } ] }`. A bundled layout (core's or a plugin's) can never be
 * deleted: its override is removed instead (reset to default), answered like [PanelGetMailLayoutAPI] plus
 * `deleted: false`.
 */
@Endpoint
class PanelDeleteMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layouts/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val id = getParameters(context).pathParameter("id").string
        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val info = support.service.layoutInfo(id, sqlClient) ?: throw NotExists()
        val name = support.layoutName(id, sqlClient)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (info.builtIn) {
            val deleted = databaseManager.mailLayoutDao.delete(info.owner, info.key, sqlClient)

            support.service.evictLayout(id)

            if (deleted) {
                databaseManager.panelActivityLogDao.add(ResetMailLayoutLog(userId, username, id, name), sqlClient)
            }

            return Successful(support.layoutDetails(id, userId, sqlClient) + mapOf("deleted" to false))
        }

        val locale = support.adminLocale(userId, sqlClient)
        val movedMails = support.layoutUsage(locale, sqlClient)[id].orEmpty()

        databaseManager.mailSettingDao.replaceLayout(id, MailTemplateIndex.DEFAULT_LAYOUT, sqlClient)
        databaseManager.mailLayoutDao.delete(info.owner, info.key, sqlClient)

        support.service.evictLayout(id)

        databaseManager.panelActivityLogDao.add(DeletedMailLayoutLog(userId, username, id, name), sqlClient)

        return Successful(mapOf("deleted" to true, "movedMails" to movedMails))
    }
}
