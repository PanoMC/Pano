package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.RegisterUtil
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class RegisterAPI(
    private val reCaptcha: ReCaptcha,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/register")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")
        val passwordRepeat = data.getString("passwordRepeat")
        val agreement = data.getBoolean("agreement")
        val recaptchaToken = data.getString("recaptcha")

        val remoteIP = context.request().remoteAddress().host()

        RegisterUtil.validateForm(
            username,
            email,
            password,
            passwordRepeat,
            agreement,
            recaptchaToken,
            null,
            (this::validateFormHandler)(
                handler,
                username,
                email,
                password,
                remoteIP
            )
        )
    }

    private fun validateFormHandler(
        handler: (result: Result) -> Unit,
        username: String,
        email: String,
        password: String,
        remoteIP: String
    ) = handler@{ result: Result ->
        if (result is Error) {
            handler.invoke(result)

            return@handler
        }

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                username,
                email,
                password,
                remoteIP
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        username: String,
        email: String,
        password: String,
        remoteIP: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        RegisterUtil.register(
            databaseManager,
            sqlConnection,
            username,
            email,
            password,
            remoteIP,
            isAdmin = false,
            isSetup = false,
            handler = (this::registerHandler)(handler, sqlConnection)
        )
    }

    private fun registerHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ result: Result, _: AsyncResult<*>? ->
        databaseManager.closeConnection(sqlConnection) {
            handler.invoke(result)
        }
    }
}