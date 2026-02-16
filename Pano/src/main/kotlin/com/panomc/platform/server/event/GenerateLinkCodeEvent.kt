package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.GenerateLinkCodeEventRequest
import com.panomc.platform.server.event.response.GenerateLinkCodeEventResponse
import java.util.Random

@Event
class GenerateLinkCodeEvent(private val databaseManager: DatabaseManager) : 
    ServerEvent<GenerateLinkCodeEventRequest, GenerateLinkCodeEventResponse>() {

    override suspend fun handle(request: GenerateLinkCodeEventRequest, server: Server): GenerateLinkCodeEventResponse? {
        val username = request.username
        val sqlClient = databaseManager.getSqlClient()

        val user = databaseManager.userDao.getByUsername(username, sqlClient) ?: return null 
        // If user not found, return null (no response). Or strictly speaking, we could return error response if protocol allows.
        // But for now, silence is fine, or simple error string in code.
        // Or create user? No, they should join first.

        val code = String.format("%06d", Random().nextInt(999999))
        val createdAt = System.currentTimeMillis()

        databaseManager.userDao.setLinkCode(username, code, createdAt, sqlClient)

        return GenerateLinkCodeEventResponse(code)
    }
}
