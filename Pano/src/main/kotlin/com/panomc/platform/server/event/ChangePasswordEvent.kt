package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.ChangePasswordEventRequest
import com.panomc.platform.server.response.ChangePasswordEventResponse

@Event
class ChangePasswordEvent(
    private val databaseManager: DatabaseManager
) : ServerEvent<ChangePasswordEventRequest, ChangePasswordEventResponse>() {
    override suspend fun handle(request: ChangePasswordEventRequest, server: Server): ChangePasswordEventResponse {
        val sqlClient = databaseManager.getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(request.username, sqlClient)!!

        databaseManager.userDao.setPasswordById(userId, request.password, sqlClient)

        return ChangePasswordEventResponse(null)
    }
}