package com.panomc.platform.util

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.error.*
import io.vertx.sqlclient.SqlClient
import org.apache.commons.codec.digest.DigestUtils

object RegisterUtil {

    fun validateForm(
        username: String? = null,
        email: String,
        password: String,
        passwordRepeat: String = password,
        agreement: Boolean
    ) {
        if (username.isNullOrEmpty()) {
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

        userId = databaseManager.userDao.add(user, hashedPassword, sqlClient, isSetup)

        if (!isAdmin) {
            return userId
        }

        databaseManager.permissionNodeDao.add(PermissionNode(
            holderType = HolderType.USER,
            holderId = userId,
            node = "group.admin",
            active = true
        ), sqlClient)

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

    fun validatePassword(newPassword: String, newPasswordRepeat: String) {
        if (newPassword.isBlank()) {
            throw NewPasswordEmpty()
        }

        if (newPassword.length < 6) {
            throw NewPasswordTooShort()
        }

        if (newPassword.length > 128) {
            throw NewPasswordTooLong()
        }

        if (newPassword != newPasswordRepeat) {
            throw NewPasswordRepeatDoesntMatch()
        }
    }
}