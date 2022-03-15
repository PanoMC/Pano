package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.User
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PlayerEditInfoAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/player/edit/info")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val id = data.getInteger("id")
        val username = data.getString("username")
        val email = data.getString("email")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")

        validateForm(handler, username, email, newPassword, newPasswordRepeat) {
            databaseManager.createConnection(
                (this::createConnectionHandler)(
                    handler,
                    id,
                    username,
                    email,
                    newPassword
                )
            )
        }
    }

    private fun validateForm(
        handler: (result: Result) -> Unit,
        username: String,
        email: String,
        newPassword: String,
        newPasswordRepeat: String,
        successHandler: () -> Unit
    ) {
        val errors = mutableMapOf<String, Any>()

        if (username.isEmpty() || username.length > 16 || username.length < 3 || !username.matches(Regex("^[a-zA-Z0-9_]+\$")))
            errors["username"] = "INVALID"

        if (email.isEmpty() || !email.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$")))
            errors["email"] = "INVALID"

        if (newPassword.isNotEmpty() && (newPassword.length < 6 || newPassword.length > 128))
            errors["newPassword"] = "INVALID"

        if (newPasswordRepeat != newPassword)
            errors["newPasswordRepeat"] = "NOT_MATCH"

        if (errors.isNotEmpty()) {
            handler.invoke(Errors(errors))

            return
        }

        successHandler.invoke()
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int,
        username: String,
        email: String,
        newPassword: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().userDao.isExistsByID(
            id,
            sqlConnection,
            (this::isExistsByIDHandler)(sqlConnection, handler, id, username, email, newPassword)
        )
    }

    private fun isExistsByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        username: String,
        email: String,
        newPassword: String
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.getByID(
            id,
            sqlConnection,
            (this::getByIDHandler)(sqlConnection, handler, username, email, newPassword)
        )
    }

    private fun getByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        email: String,
        newPassword: String
    ) = handler@{ user: User?, _: AsyncResult<*> ->
        if (user == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (username == user.username) {
            if (email == user.email) {
                if (newPassword.isEmpty()) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Successful())
                    }

                    return@handler
                }

                databaseManager.getDatabase().userDao.setPasswordByID(
                    user.id,
                    newPassword,
                    sqlConnection,
                    (this::setPasswordByIDHandler)(sqlConnection, handler)
                )

                return@handler
            }

            databaseManager.getDatabase().userDao.isEmailExists(
                email,
                sqlConnection,
                (this::isEmailExistsHandler)(
                    sqlConnection,
                    handler,
                    user.id,
                    username,
                    email,
                    newPassword
                )
            )

            return@handler
        }

        databaseManager.getDatabase().userDao.isExistsByUsername(
            username,
            sqlConnection,
            (this::isExistsByUsernameHandler)(
                sqlConnection,
                handler,
                user.id,
                user,
                username,
                email,
                newPassword
            )
        )
    }

    private fun isExistsByUsernameHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        user: User,
        username: String,
        email: String,
        newPassword: String
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Errors(mapOf("username" to "EXISTS")))
            }

            return@handler
        }

        if (email == user.email) {
            databaseManager.getDatabase().userDao.setUsernameByID(
                id,
                username,
                sqlConnection,
                (this::setUsernameByIDHandler)(sqlConnection, handler, id, email, newPassword)
            )

            return@handler
        }

        databaseManager.getDatabase().userDao.isEmailExists(
            email,
            sqlConnection,
            (this::isEmailExistsHandler)(sqlConnection, handler, id, username, email, newPassword)
        )
    }

    private fun isEmailExistsHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        username: String,
        email: String,
        newPassword: String
    ) = handler@{ isEmailExists: Boolean?, _: AsyncResult<*> ->
        if (isEmailExists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (isEmailExists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Errors(mapOf("email" to "EXISTS")))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.setUsernameByID(
            id,
            username,
            sqlConnection,
            (this::setUsernameByIDHandler)(sqlConnection, handler, id, email, newPassword)
        )
    }

    private fun setUsernameByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        email: String,
        newPassword: String
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.setEmailByID(
            id,
            email,
            sqlConnection,
            (this::setEmailByIDHandler)(sqlConnection, handler, id, newPassword)
        )
    }

    private fun setEmailByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        newPassword: String
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (newPassword.isEmpty()) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Successful())
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.setPasswordByID(
            id,
            newPassword,
            sqlConnection,
            (this::setPasswordByIDHandler)(sqlConnection, handler)
        )
    }

    private fun setPasswordByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}