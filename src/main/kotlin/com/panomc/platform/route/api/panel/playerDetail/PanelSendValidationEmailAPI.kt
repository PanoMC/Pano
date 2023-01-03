package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ActivationMail
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelSendValidationEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/verificationMail", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlConnection = createConnection(databaseManager, context)

        val isExists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!isExists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsername(username, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        val isEmailVerified = databaseManager.userDao.isEmailVerifiedById(userId, sqlConnection)

        if (isEmailVerified) {
            throw Error(ErrorCode.EMAIL_ALREADY_VERIFIED)
        }

        mailManager.sendMail(sqlConnection, userId, ActivationMail())

        return Successful()
    }
}