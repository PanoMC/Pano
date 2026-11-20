package com.panomc.platform.util

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.error.*
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import java.util.*

object RegisterUtil {

    /**
     * `system_property` key holding the id of the admin the setup wizard created. Also read by
     * TelemetryManager to date the installation.
     */
    const val WHO_INSTALLED_USER_ID = "who_installed_user_id"

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

        if (password.isBlank()) {
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

    /**
     * Generate an offline-mode UUID from a username, matching the formula
     * used by cracked/offline Minecraft servers.
     */
    fun generateOfflineUuid(username: String): String {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:$username").toByteArray(Charsets.UTF_8)
        ).toString()
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
        mcUuid: String? = null,
    ): Long = registerUser(databaseManager, sqlClient, username, email, remoteIP, isAdmin, isSetup, mcUuid) {
        hashPassword(password)
    }

    /**
     * Hashes [password] with the configured algorithm, off the event loop. Pair it with
     * [registerWithHashedPassword] to hash before opening a transaction, so no pooled connection or row lock is
     * held while the slow hash runs.
     */
    suspend fun hashPassword(password: String): String {
        val passwordHasher = applicationContext.getBean(PasswordHasher::class.java)
        val configManager = applicationContext.getBean(ConfigManager::class.java)
        val algorithm = PasswordHasher.Algorithm.fromString(configManager.config.auth.passwordHashAlgorithm)

        return passwordHasher.hashAsync(password, algorithm)
    }

    /**
     * The same as [register], with a password already hashed by [hashPassword]. Runs the same checks (IP ban,
     * username and email taken) and writes the same rows.
     */
    suspend fun registerWithHashedPassword(
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        username: String,
        email: String,
        hashedPassword: String,
        remoteIP: String,
        isAdmin: Boolean = false,
        isSetup: Boolean = false,
        mcUuid: String? = null,
    ): Long = registerUser(databaseManager, sqlClient, username, email, remoteIP, isAdmin, isSetup, mcUuid) {
        hashedPassword
    }

    private suspend fun registerUser(
        databaseManager: DatabaseManager,
        sqlClient: SqlClient,
        username: String,
        email: String,
        remoteIP: String,
        isAdmin: Boolean,
        isSetup: Boolean,
        mcUuid: String?,
        hashedPassword: suspend () -> String,
    ): Long {
        if (!isSetup) {
            val activeIpBan = databaseManager.bannedIpDao.getActiveByIp(remoteIP, sqlClient)

            if (activeIpBan != null) {
                throw IpIsBanned(
                    extras = mapOf(
                        "reason" to activeIpBan.reason,
                        "bannedUntil" to activeIpBan.bannedUntil
                    )
                )
            }
        }

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

        // Use premium UUID if provided, otherwise generate offline UUID
        val resolvedUuid = mcUuid ?: generateOfflineUuid(username)

        val user = User(username = username, email = email, registeredIp = remoteIP, mcUuid = resolvedUuid)
        val passwordHash = hashedPassword()

        // A concurrent registration of the same name or address that won the race hits the unique key here:
        // answered like the checks above, not as a 500.
        val userId = try {
            databaseManager.userDao.add(user, passwordHash, sqlClient, isSetup)
        } catch (e: Exception) {
            val duplicate = e.duplicateKeyMessage() ?: throw e

            if (duplicate.contains("email", ignoreCase = true)) throw RegisterEmailNotAvailable()

            throw RegisterUsernameNotAvailable()
        }

        if (!isAdmin) {
            return userId
        }

        databaseManager.permissionNodeDao.add(PermissionNode(
            holderType = HolderType.USER,
            holderId = userId,
            node = "group.admin",
            active = true
        ), sqlClient)

        val property = SystemProperty(option = WHO_INSTALLED_USER_ID, value = userId.toString())

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

    /** The first duplicate-key (`1062`) message in the cause chain (it names the key), `null` when there is none. */
    private fun Throwable.duplicateKeyMessage(): String? =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
            .take(8)
            .firstOrNull { (it is MySQLException && it.errorCode == ER_DUP_ENTRY) || it.message?.contains("Duplicate entry", ignoreCase = true) == true }
            ?.let { it.message.orEmpty() }

    private const val ER_DUP_ENTRY = 1062
}