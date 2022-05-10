package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.AuthProvider
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class LoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/login")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val usernameOrEmail = data.getString("usernameOrEmail")
        val password = data.getString("password")
        val rememberMe = data.getBoolean("rememberMe")
        val recaptcha = data.getString("recaptcha")

        fun loginHandler(sqlConnection: SqlConnection) = loginHandler@{ result: Result ->
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(result)
            }
        }

        fun authenticateHandler(sqlConnection: SqlConnection) = authenticateHandler@{ result: Result ->
            if (result is Error) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(result)
                }

                return@authenticateHandler
            }

            authProvider.login(usernameOrEmail, sqlConnection, loginHandler(sqlConnection))
        }

        val createConnectionHandler =
            createConnectionHandler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
                if (sqlConnection == null) {
                    handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                    return@createConnectionHandler
                }

                authProvider.authenticate(
                    usernameOrEmail,
                    password,
                    sqlConnection,
                    authenticateHandler(sqlConnection)
                )
            }

        val inputValidatorHandler = inputValidatorHandler@{ result: Result ->
            if (result is Error) {
                handler.invoke(result)

                return@inputValidatorHandler
            }

            databaseManager.createConnection(createConnectionHandler)
        }

        authProvider.inputValidator(usernameOrEmail, password, recaptcha, inputValidatorHandler)
    }
}