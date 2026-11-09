package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.SecureChannel
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.event.request.ChangePasswordEventRequest
import com.panomc.platform.server.response.ChangePasswordEventResponse
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import io.vertx.core.json.JsonObject

@Event
class ChangePasswordEvent(
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager,
    private val tokenProvider: TokenProvider,
    private val serverManager: ServerManager
) : ServerEvent<ChangePasswordEventRequest, ChangePasswordEventResponse>() {
    override suspend fun handle(request: ChangePasswordEventRequest, server: Server): ChangePasswordEventResponse {
        // Password changes through a server are only accepted while its auth integration is on.
        if (!server.settings.authIntegration) {
            return ChangePasswordEventResponse(AUTH_INTEGRATION_DISABLED)
        }

        if (request.password.isBlank() || request.password.length < 6 || request.password.length > 128) {
            return ChangePasswordEventResponse("INVALID_PASSWORD")
        }

        val sqlClient = databaseManager.getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(request.username, sqlClient)
            ?: return ChangePasswordEventResponse(USER_NOT_FOUND)

        // A plugin that supports it proves the player knows the current password, unless it is a
        // staff / console reset (MC-PROTOCOL-SPEC.md section 3). Older plugins cannot send it.
        if (requiresCurrentPassword(serverManager.hasCapability(server.id, SecureChannel.CAP_PASSWORD_CHECK), request)) {
            val current = request.currentPassword

            if (current.isNullOrEmpty()) {
                return ChangePasswordEventResponse(CURRENT_PASSWORD_REQUIRED)
            }

            if (attemptLimiter.isBlocked(server.id, request.username)) {
                return ChangePasswordEventResponse(RATE_LIMITED)
            }

            if (!databaseManager.userDao.isLoginCorrect(request.username, current, sqlClient)) {
                attemptLimiter.recordFailure(server.id, request.username)

                return ChangePasswordEventResponse(CURRENT_PASSWORD_INVALID)
            }

            attemptLimiter.recordSuccess(server.id, request.username)
        }

        // Accounts with any panel access (staff, admins) change their password on the website
        // only: a server must never be able to take one over.
        val grantedNodes = permissionManager.getGrantedNodes(userId, anyServer = true)
        if (grantedNodes.any { ServerPermissionSyncGuard.isPanelNode(it, JsonObject()) }) {
            return ChangePasswordEventResponse(PANEL_ACCOUNT)
        }

        databaseManager.userDao.setPasswordById(userId, request.password, sqlClient)

        // Sign the account out everywhere, like a password change on the website would want.
        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), AuthenticationTokenType, sqlClient)

        return ChangePasswordEventResponse(null)
    }

    private val attemptLimiter = ServerAuthAttemptLimiter()

    companion object {
        const val CURRENT_PASSWORD_REQUIRED = "CURRENT_PASSWORD_REQUIRED"
        const val CURRENT_PASSWORD_INVALID = "CURRENT_PASSWORD_INVALID"
        const val RATE_LIMITED = "RATE_LIMITED"

        /** Whether [request] must prove the current password: only plugins with `password-check`, never a reset. */
        fun requiresCurrentPassword(passwordCheck: Boolean, request: ChangePasswordEventRequest): Boolean =
            passwordCheck && request.reset != true
        const val AUTH_INTEGRATION_DISABLED = "AUTH_INTEGRATION_DISABLED"
        const val USER_NOT_FOUND = "USER_NOT_FOUND"
        const val PANEL_ACCOUNT = "PANEL_ACCOUNT"
    }
}
