package com.panomc.platform.server.event

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.annotation.Event
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.User
import com.panomc.platform.error.RegisterUsernameNotAvailable
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.RegisterPlayerEventRequest
import com.panomc.platform.server.response.RegisterPlayerEventResponse
import com.panomc.platform.util.PasswordHasher

@Event
class RegisterPlayerEvent(
    private val databaseManager: DatabaseManager,
) : ServerEvent<RegisterPlayerEventRequest, RegisterPlayerEventResponse>() {
    override suspend fun handle(request: RegisterPlayerEventRequest, server: Server): RegisterPlayerEventResponse {
        // Accounts are created through a server only while that server's auth integration is on.
        if (!server.settings.authIntegration) {
            return RegisterPlayerEventResponse(AUTH_INTEGRATION_DISABLED)
        }

        val sqlClient = databaseManager.getSqlClient()

        val isUsernameExists = databaseManager.userDao.existsByUsername(request.username, sqlClient)

        if (isUsernameExists) {
            return RegisterPlayerEventResponse( RegisterUsernameNotAvailable().getErrorCode())
        }

        val user = User(username = request.username, email = null, registeredIp = request.ipAddress)

        val passwordHasher = applicationContext.getBean(PasswordHasher::class.java)
        val configManager = applicationContext.getBean(ConfigManager::class.java)
        val algorithm = PasswordHasher.Algorithm.fromString(configManager.config.auth.passwordHashAlgorithm)
        val hashedPassword = passwordHasher.hashAsync(request.password, algorithm)

        databaseManager.userDao.add(user, hashedPassword, sqlClient, false)

        return RegisterPlayerEventResponse(null)
    }

    companion object {
        const val AUTH_INTEGRATION_DISABLED = "AUTH_INTEGRATION_DISABLED"
    }
}
