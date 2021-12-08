package com.panomc.platform.route.api.setup

import com.panomc.platform.ErrorCode
import com.panomc.platform.Main.Companion.getComponent
import com.panomc.platform.model.*
import com.panomc.platform.util.RegisterUtil
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class FinishAPI : SetupApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/finish")

    init {
        getComponent().inject(this)
    }

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        if (setupManager.getStep() != 3) {
            handler.invoke(Successful(setupManager.getCurrentStepData()))

            return
        }

        val data = context.bodyAsJson

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")

        val remoteIP = context.request().remoteAddress().host()

        RegisterUtil.validateForm(
            username,
            email,
            password,
            password,
            true,
            "",
            null,
            (this::validateFormHandler)(handler, context, username, email, password, remoteIP)
        )
    }

    private fun validateFormHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
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
                context,
                username,
                email,
                password,
                remoteIP
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        username: String,
        email: String,
        password: String,
        remoteIP: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.initDatabase(
            sqlConnection,
            (this::initDatabaseHandler)(handler, context, sqlConnection, username, email, password, remoteIP)
        )
    }

    private fun initDatabaseHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        sqlConnection: SqlConnection,
        username: String,
        email: String,
        password: String,
        remoteIP: String
    ) = handler@{ asyncResult: AsyncResult<*> ->
        if (asyncResult.failed()) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        RegisterUtil.register(
            databaseManager,
            sqlConnection,
            username,
            email,
            password,
            remoteIP,
            isAdmin = true,
            isSetup = true,
            handler = (this::registerHandler)(handler, context, sqlConnection, username)
        )
    }

    private fun registerHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        sqlConnection: SqlConnection,
        username: String,
    ) = handler@{ result: Result, _: AsyncResult<*>? ->
        if (result is Error) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(result)
            }

            return@handler
        }

        authProvider.login(
            username,
            sqlConnection,
            (this::loginHandler)(handler, sqlConnection)
        )
    }

    private fun loginHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ isLoggedIn: Any ->
        databaseManager.closeConnection(sqlConnection) {
            if (isLoggedIn is Error) {
                handler.invoke(isLoggedIn)

                return@closeConnection
            }

            if (isLoggedIn is Boolean && !isLoggedIn) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            setupManager.finishSetup()

            handler.invoke(Successful())
        }
    }
}