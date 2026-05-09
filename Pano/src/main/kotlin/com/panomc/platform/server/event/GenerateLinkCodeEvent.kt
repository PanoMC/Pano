package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.GenerateLinkCodeEventRequest
import com.panomc.platform.server.event.response.GenerateLinkCodeEventResponse
import com.panomc.platform.server.event.response.GenerateLinkCodeStatus
import java.security.SecureRandom

@Event
class GenerateLinkCodeEvent(private val databaseManager: DatabaseManager) :
    ServerEvent<GenerateLinkCodeEventRequest, GenerateLinkCodeEventResponse>() {

    companion object {
        // Lifetime of a generated link code in milliseconds. Must stay in sync with
        // VerifyLinkCodeAPI's expiry check (Calendar.SECOND, 30).
        private const val LINK_CODE_TTL_MS = 30_000L
    }

    private val secureRandom = SecureRandom()

    override suspend fun handle(request: GenerateLinkCodeEventRequest, server: Server): GenerateLinkCodeEventResponse {
        val username = request.username
        val sqlClient = databaseManager.getSqlClient()

        val user = databaseManager.userDao.getByUsername(username, sqlClient)
            ?: return GenerateLinkCodeEventResponse(status = GenerateLinkCodeStatus.USER_NOT_FOUND)

        // Mirror LoginAPI's LinkCodeRequired condition: /link only makes sense for users
        // that have neither password nor email set. Any other state means the user can
        // log in via the regular flow and shouldn't be issued a new link code.
        val hasPassword = databaseManager.userDao.hasPassword(user.id, sqlClient)
        val hasEmail = !user.email.isNullOrEmpty()
        if (hasPassword || hasEmail) {
            return GenerateLinkCodeEventResponse(status = GenerateLinkCodeStatus.ALREADY_REGISTERED)
        }

        val now = System.currentTimeMillis()

        // Reuse an existing code while it is still valid so spamming /link does not
        // rotate the code that the user is currently trying to enter on the website.
        val existing = databaseManager.userDao.getLinkCode(username, sqlClient)
        val existingCode = existing?.first
        val existingCreatedAt = existing?.second ?: 0L
        if (!existingCode.isNullOrEmpty() && now - existingCreatedAt < LINK_CODE_TTL_MS) {
            return GenerateLinkCodeEventResponse(code = existingCode)
        }

        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        databaseManager.userDao.setLinkCode(username, code, now, sqlClient)

        return GenerateLinkCodeEventResponse(code = code)
    }
}
