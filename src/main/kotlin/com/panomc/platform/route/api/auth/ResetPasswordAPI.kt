package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.MailType
import com.panomc.platform.util.MailUtil
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class ResetPasswordAPI(
    private val mailUtil: MailUtil,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/resetPassword")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("usernameOrEmail", Schemas.stringSchema())
//                TODO: Add recaptcha
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")

        validateInput(usernameOrEmail)

        val sqlConnection = databaseManager.createConnection()

        val exists = databaseManager.userDao.isExistsByUsernameOrEmail(usernameOrEmail, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlConnection) ?: throw Error(
                ErrorCode.NOT_EXISTS
            )

        mailUtil.sendMail(sqlConnection, userId, MailType.RESET_PASSWORD)

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