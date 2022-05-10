package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

object RegisterUtil {

    fun validateForm(
        username: String,
        email: String,
        password: String,
        passwordRepeat: String = password,
        agreement: Boolean,
        recaptchaToken: String = "",
        reCaptcha: ReCaptcha? = null,
        handler: (result: Result) -> Unit
    ) {
        if (username.isEmpty()) {
            handler.invoke(Error(ErrorCode.REGISTER_USERNAME_EMPTY))

            return
        }

        if (email.isEmpty()) {
            handler.invoke(Error(ErrorCode.REGISTER_EMAIL_EMPTY))

            return
        }

        if (password.isEmpty()) {
            handler.invoke(Error(ErrorCode.REGISTER_PASSWORD_EMPTY))

            return
        }

        if (username.length < 3) {
            handler.invoke(Error(ErrorCode.REGISTER_USERNAME_TOO_SHORT))

            return
        }

        if (username.length > 16) {
            handler.invoke(Error(ErrorCode.REGISTER_USERNAME_TOO_LONG))

            return
        }

        if (password.length < 6) {
            handler.invoke(Error(ErrorCode.REGISTER_PASSWORD_TOO_SHORT))

            return
        }

        if (password.length > 128) {
            handler.invoke(Error(ErrorCode.REGISTER_PASSWORD_TOO_LONG))

            return
        }

        if (!username.matches(Regex("^[a-zA-Z0-9_]+\$"))) {
            handler.invoke(Error(ErrorCode.REGISTER_INVALID_USERNAME))

            return
        }

        if (!email.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$"))) {
            handler.invoke(Error(ErrorCode.REGISTER_INVALID_USERNAME))

            return
        }

        if (password != passwordRepeat) {
            handler.invoke(Error(ErrorCode.REGISTER_PASSWORD_AND_PASSWORD_REPEAT_NOT_SAME))

            return
        }

        if (!agreement) {
            handler.invoke(Error(ErrorCode.REGISTER_NOT_ACCEPTED_AGREEMENT))
        }

        if (reCaptcha != null && !reCaptcha.isValid(recaptchaToken)) {
            handler.invoke(Error(ErrorCode.REGISTER_CANT_VERIFY_ROBOT))

            return
        }

        handler.invoke(Successful())
    }

    fun register(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isAdmin: Boolean = false,
        isSetup: Boolean = false,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit
    ) {
        databaseManager.userDao.isExistsByUsername(
            username,
            sqlConnection,
            (this::isExistsByUsernameHandler)(
                databaseManager,
                sqlConnection,
                handler,
                username,
                email,
                password,
                remoteIP,
                isAdmin,
                isSetup
            )
        )
    }

    private fun isExistsByUsernameHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isAdmin: Boolean,
        isSetup: Boolean
    ) = handler@{ exists: Boolean?, asyncResult: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN), asyncResult)
            }

            return@handler
        }

        if (exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.REGISTER_USERNAME_NOT_AVAILABLE), null)
            }

            return@handler
        }

        databaseManager.userDao.isEmailExists(
            email,
            sqlConnection,
            (this::isEmailExistsHandler)(
                databaseManager,
                sqlConnection,
                handler,
                username,
                email,
                password,
                remoteIP,
                isAdmin,
                isSetup
            )
        )
    }

    private fun isEmailExistsHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isAdmin: Boolean,
        isSetup: Boolean
    ) = handler@{ exists: Boolean?, asyncResult: AsyncResult<*> ->
        if (exists == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        if (exists) {
            handler.invoke(Error(ErrorCode.REGISTER_EMAIL_NOT_AVAILABLE), null)

            return@handler
        }

        val user = User(-1, username, email, password, remoteIP, -1, System.currentTimeMillis())

        if (!isAdmin) {
            addUser(user, databaseManager, sqlConnection, isSetup, handler)

            return@handler
        }

        databaseManager.permissionGroupDao.getPermissionGroupID(
            PermissionGroup(-1, "admin"),
            sqlConnection,
            (this::getPermissionGroupIDHandler)(
                databaseManager,
                sqlConnection,
                handler,
                username,
                email,
                password,
                remoteIP,
                isSetup
            )
        )
    }

    private fun getPermissionGroupIDHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isSetup: Boolean
    ) = handler@{ permissionGroupID: Int?, asyncResult: AsyncResult<*> ->
        if (permissionGroupID == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        val adminUser = User(
            -1,
            username,
            email,
            password,
            remoteIP,
            permissionGroupID,
            System.currentTimeMillis()
        )

        addUser(
            adminUser,
            databaseManager,
            sqlConnection,
            isSetup,
            (this::addUserHandler)(databaseManager, sqlConnection, handler, username)
        )
    }

    private fun addUserHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit,
        username: String
    ) = handler@{ result: Result?, asyncResult: AsyncResult<*> ->
        if (result == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        databaseManager.userDao.getUserIDFromUsername(
            username,
            sqlConnection,
            (this::getUserIDFromUsernameHandler)(databaseManager, sqlConnection, handler)
        )
    }

    private fun getUserIDFromUsernameHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit
    ) = handler@{ userID: Int?, asyncResult: AsyncResult<*> ->
        if (userID == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        val property = SystemProperty(-1, "who_installed_user_id", userID.toString())

        databaseManager.systemPropertyDao.isPropertyExists(
            property,
            sqlConnection,
            (this::isPropertyExistsHandler)(databaseManager, sqlConnection, handler, property)
        )
    }

    private fun isPropertyExistsHandler(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit,
        property: SystemProperty
    ) = handler@{ exists: Boolean?, asyncResult: AsyncResult<*> ->
        if (exists == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        if (exists) {
            databaseManager.systemPropertyDao.update(
                property,
                sqlConnection,
                (this::updateHandler)(handler)
            )

            return@handler
        }

        databaseManager.systemPropertyDao.add(
            property,
            sqlConnection,
            (this::addHandler)(handler)
        )
    }

    private fun updateHandler(
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit
    ) = handler@{ result: Result?, asyncResult: AsyncResult<*> ->
        if (result == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        handler.invoke(Successful(), asyncResult)
    }

    private fun addHandler(
        handler: (result: Result, asyncResult: AsyncResult<*>?) -> Unit
    ) = handler@{ result: Result?, asyncResult: AsyncResult<*> ->
        if (result == null) {
            handler.invoke(
                Error(ErrorCode.UNKNOWN),
                asyncResult
            )

            return@handler
        }

        handler.invoke(Successful(), asyncResult)
    }

    private fun addUser(
        user: User,
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        isSetup: Boolean,
        handler: (result: Result, asyncResult: AsyncResult<*>) -> Unit
    ) {
        databaseManager.userDao.add(user, sqlConnection, isSetup) { isSuccessful, asyncResultOfAdd ->
            if (isSuccessful == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN), asyncResultOfAdd)

                return@add
            }

            handler.invoke(Successful(), asyncResultOfAdd)
        }
    }
}