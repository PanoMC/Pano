package com.panomc.platform.util

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.error.*
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.sqlclient.SqlClient
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
            throw RegisterUsernameEmpty()
        }

        if (email.isEmpty()) {
            throw RegisterEmailEmpty()
        }

        if (password.isEmpty()) {
            throw PasswordEmpty()
        }

        if (username.length < 3) {
            throw RegisterUsernameTooShort()
        }

        if (username.length > 16) {
            throw RegisterUsernameTooLong()
        }

        if (password.length < 6) {
            throw PasswordTooShort()
        }

        if (password.length > 128) {
            throw PasswordTooLong()
        }

        if (!username.matches(Regex(Regexes.USERNAME))) {
            throw RegisterInvalidUsername()
        }

        if (!email.matches(Regex(Regexes.EMAIL))) {
            throw RegisterInvalidEmail()
        }

        if (password != passwordRepeat) {
            throw RegisterPasswordAndPasswordRepeatNotSame()
        }

        if (!agreement) {
            throw RegisterNotAcceptedAgreement()
        }

        if (reCaptcha != null && !reCaptcha.isValid(recaptchaToken)) {
            throw RegisterCantVerifyRobot()
        }
    }

    suspend fun register(
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        username: String,
        email: String,
        password: String,
        remoteIP: String,
        isAdmin: Boolean = false,
        isSetup: Boolean = false,
    ): Long {
        val isUsernameExists = databaseManager.userDao.existsByUsername(
            username,
            sqlClient
        )

        if (isUsernameExists) {
            throw RegisterUsernameNotAvailable()
        }

        val isEmailExists = databaseManager.userDao.isEmailExists(email, sqlClient)

        if (isEmailExists) {
            throw RegisterEmailNotAvailable()
        }

        val user = User(username = username, email = email, registeredIp = remoteIP)
        val userId: Long

        val hashedPassword = DigestUtils.md5Hex(password)

        if (!isAdmin) {
            userId = databaseManager.userDao.add(user, hashedPassword, sqlClient, isSetup)

            return userId
        }

        val adminPermissionGroupId = databaseManager.permissionGroupDao.getPermissionGroupIdByName(
            "admin",
            sqlClient
        )!!

        val adminUser = User(
            username = username,
            email = email,
            registeredIp = remoteIP,
            permissionGroupId = adminPermissionGroupId
        )

        userId = databaseManager.userDao.add(adminUser, hashedPassword, sqlClient, isSetup)
        val property = SystemProperty(option = "who_installed_user_id", value = userId.toString())

        val isPropertyExists = databaseManager.systemPropertyDao.existsByOption(
            property.option,
            sqlClient
        )

        if (isPropertyExists) {
            databaseManager.systemPropertyDao.update(
                property.option,
                property.value,
                sqlClient
            )

            return userId
        }

        databaseManager.systemPropertyDao.add(
            property,
            sqlClient
        )

        return userId
    }
}