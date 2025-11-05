package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.User
import com.panomc.platform.error.RegisterUsernameNotAvailable
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.RegisterPlayerEventRequest
import com.panomc.platform.server.response.RegisterPlayerEventResponse
import org.apache.commons.codec.digest.DigestUtils

@Event
class RegisterPlayerEvent(
    private val databaseManager: DatabaseManager,
) : ServerEvent<RegisterPlayerEventRequest, RegisterPlayerEventResponse>() {
    override suspend fun handle(request: RegisterPlayerEventRequest, server: Server): RegisterPlayerEventResponse {
        val sqlClient = databaseManager.getSqlClient()

        val isUsernameExists = databaseManager.userDao.existsByUsername(request.username, sqlClient)

        if (isUsernameExists) {
            return RegisterPlayerEventResponse( RegisterUsernameNotAvailable().getErrorCode())
        }

        val user = User(username = request.username, email = null, registeredIp = request.ipAddress)

        val hashedPassword = DigestUtils.md5Hex(request.password)

        databaseManager.userDao.add(user, hashedPassword, sqlClient, false)

        return RegisterPlayerEventResponse(null)
    }
}