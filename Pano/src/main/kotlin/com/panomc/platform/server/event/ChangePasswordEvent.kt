package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.ChangePasswordEventRequest
import com.panomc.platform.server.response.ChangePasswordEventResponse
import org.apache.commons.codec.digest.DigestUtils

@Event
class ChangePasswordEvent(
    private val databaseManager: DatabaseManager,
) : ServerEvent<ChangePasswordEventRequest>() {
    override suspend fun handle(request: ChangePasswordEventRequest, server: Server): PlatformMessage {
        val sqlClient = databaseManager.getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(request.username, sqlClient)!!

        val hashedPassword = DigestUtils.md5Hex(request.password)

        databaseManager.userDao.setPasswordById(userId, hashedPassword, sqlClient)

        return ChangePasswordEventResponse(request.eventId, null)
    }
}