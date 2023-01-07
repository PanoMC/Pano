package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ResetPasswordMail
import com.panomc.platform.model.*
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class ResetPasswordAPI(
    private val mailManager: MailManager,
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/api/auth/resetPassword", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("usernameOrEmail", Schemas.stringSchema())
//                TODO: Add recaptcha
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")

        validateInput(usernameOrEmail)

        val sqlConnection = createConnection(context)

        val exists = databaseManager.userDao.isExistsByUsernameOrEmail(usernameOrEmail, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlConnection) ?: throw Error(
                ErrorCode.NOT_EXISTS
            )

        mailManager.sendMail(sqlConnection, userId, ResetPasswordMail())

        return Successful()
    }

    private fun validateInput(usernameOrEmail: String) {
        if (usernameOrEmail.isBlank()) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        if (!usernameOrEmail.matches(Regex(Regexes.USERNAME)) && !usernameOrEmail.matches(Regex(Regexes.EMAIL))) {
            throw Error(ErrorCode.NOT_EXISTS)
        }
    }
}