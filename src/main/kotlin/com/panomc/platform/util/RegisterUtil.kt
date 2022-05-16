package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Error
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.sqlclient.SqlConnection

object RegisterUtil {

    fun validateForm(
        username: String,
        email: String,
        password: String,
        passwordRepeat: String = password,
        agreement: Boolean,
        recaptchaToken: String = "",
        reCaptcha: ReCaptcha? = null
    ) {
        if (username.isEmpty()) {
            throw Error(ErrorCode.REGISTER_USERNAME_EMPTY)
        }

        if (email.isEmpty()) {
            throw Error(ErrorCode.REGISTER_EMAIL_EMPTY)
        }

        if (password.isEmpty()) {
            throw Error(ErrorCode.REGISTER_PASSWORD_EMPTY)
        }

        if (username.length < 3) {
            throw Error(ErrorCode.REGISTER_USERNAME_TOO_SHORT)
        }

        if (username.length > 16) {
            throw Error(ErrorCode.REGISTER_USERNAME_TOO_LONG)
        }

        if (password.length < 6) {
            throw Error(ErrorCode.REGISTER_PASSWORD_TOO_SHORT)
        }

        if (password.length > 128) {
            throw Error(ErrorCode.REGISTER_PASSWORD_TOO_LONG)
        }

        if (!username.matches(Regex("^[a-zA-Z0-9_]+\$"))) {
            throw Error(ErrorCode.REGISTER_INVALID_USERNAME)
        }

        if (!email.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$"))) {
            throw Error(ErrorCode.REGISTER_INVALID_USERNAME)
        }

        if (password != passwordRepeat) {
            throw Error(ErrorCode.REGISTER_PASSWORD_AND_PASSWORD_REPEAT_NOT_SAME)
        }

        if (!agreement) {
            throw Error(ErrorCode.REGISTER_NOT_ACCEPTED_AGREEMENT)
        }

        if (reCaptcha != null && !reCaptcha.isValid(recaptchaToken)) {
            throw Error(ErrorCode.REGISTER_CANT_VERIFY_ROBOT)
        }
    }

    suspend fun register(
        databaseManager: DatabaseManager,
        sqlConnection: SqlConnection,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isAdmin: Boolean = false,
        isSetup: Boolean = false,
    ) {
        val isUsernameExists = databaseManager.userDao.isExistsByUsername(
            username,
            sqlConnection
        )

        if (isUsernameExists) {
            throw Error(ErrorCode.REGISTER_USERNAME_NOT_AVAILABLE)
        }

        val isEmailExists = databaseManager.userDao.isEmailExists(email, sqlConnection)

        if (isEmailExists) {
            throw Error(ErrorCode.REGISTER_EMAIL_NOT_AVAILABLE)
        }

        val user = User(-1, username, email, password, remoteIP, -1, System.currentTimeMillis())

        if (!isAdmin) {
            databaseManager.userDao.add(user, sqlConnection, isSetup)

            return
        }

        val adminPermissionGroupId = databaseManager.permissionGroupDao.getPermissionGroupID(
            PermissionGroup(-1, "admin"),
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val adminUser = User(
            -1,
            username,
            email,
            password,
            remoteIP,
            adminPermissionGroupId,
            System.currentTimeMillis()
        )

        val userId = databaseManager.userDao.add(adminUser, sqlConnection, isSetup)
        val property = SystemProperty(-1, "who_installed_user_id", userId.toString())

        val isPropertyExists = databaseManager.systemPropertyDao.isPropertyExists(
            property,
            sqlConnection
        )

        if (isPropertyExists) {
            databaseManager.systemPropertyDao.update(
                property,
                sqlConnection
            )

            return
        }

        databaseManager.systemPropertyDao.add(
            property,
            sqlConnection
        )
    }
}