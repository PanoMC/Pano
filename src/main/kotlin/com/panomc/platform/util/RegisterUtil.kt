package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Error
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.sqlclient.SqlConnection
import org.apache.commons.codec.digest.DigestUtils

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
            throw Error(ErrorCode.PASSWORD_EMPTY)
        }

        if (username.length < 3) {
            throw Error(ErrorCode.REGISTER_USERNAME_TOO_SHORT)
        }

        if (username.length > 16) {
            throw Error(ErrorCode.REGISTER_USERNAME_TOO_LONG)
        }

        if (password.length < 6) {
            throw Error(ErrorCode.PASSWORD_TOO_SHORT)
        }

        if (password.length > 128) {
            throw Error(ErrorCode.PASSWORD_TOO_LONG)
        }

        if (!username.matches(Regex(Regexes.USERNAME))) {
            throw Error(ErrorCode.REGISTER_INVALID_USERNAME)
        }

        if (!email.matches(Regex(Regexes.EMAIL))) {
            throw Error(ErrorCode.REGISTER_INVALID_EMAIL)
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
    ): Long {
        val isUsernameExists = databaseManager.userDao.existsByUsername(
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

        val user = User(username = username, email = email, registeredIp = remoteIP)
        val userId: Long

        val hashedPassword = DigestUtils.md5Hex(password)

        if (!isAdmin) {
            userId = databaseManager.userDao.add(user, hashedPassword, sqlConnection, isSetup)

            return userId
        }

        val adminPermissionGroupId = databaseManager.permissionGroupDao.getPermissionGroupIdByName(
            "admin",
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val adminUser = User(
            username = username,
            email = email,
            registeredIp = remoteIP,
            permissionGroupId = adminPermissionGroupId
        )

        userId = databaseManager.userDao.add(adminUser, hashedPassword, sqlConnection, isSetup)
        val property = SystemProperty(option = "who_installed_user_id", value = userId.toString())

        val isPropertyExists = databaseManager.systemPropertyDao.existsByOption(
            property.option,
            sqlConnection
        )

        if (isPropertyExists) {
            databaseManager.systemPropertyDao.update(
                property.option,
                property.value,
                sqlConnection
            )

            return userId
        }

        databaseManager.systemPropertyDao.add(
            property,
            sqlConnection
        )

        return userId
    }
}