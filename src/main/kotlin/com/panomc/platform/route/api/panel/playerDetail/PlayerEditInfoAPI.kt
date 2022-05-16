package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PlayerEditInfoAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/player/edit/info")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val id = data.getInteger("id")
        val username = data.getString("username")
        val email = data.getString("email")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")

        validateForm(username, email, newPassword, newPasswordRepeat)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.userDao.isExistsByID(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val user = databaseManager.userDao.getByID(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        if (username != user.username) {
            val usernameExists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

            if (usernameExists) {
                throw Errors(mapOf("username" to "EXISTS"))
            }

            databaseManager.userDao.setUsernameByID(user.id, username, sqlConnection)
        }

        if (email != user.email) {
            val emailExists = databaseManager.userDao.isEmailExists(email, sqlConnection)

            if (emailExists) {
                throw Errors(mapOf("username" to "EXISTS"))
            }

            databaseManager.userDao.setEmailByID(user.id, username, sqlConnection)
        }

        if (newPassword.isNotEmpty()) {
            databaseManager.userDao.setPasswordByID(user.id, newPassword, sqlConnection)
        }

        return Successful()
    }

    private fun validateForm(
        username: String,
        email: String,
        newPassword: String,
        newPasswordRepeat: String
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
            throw Errors(errors)
        }
    }
}