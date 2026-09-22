package com.panomc.platform.auth

import com.panomc.platform.auth.panel.log.BannedPlayerLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.BanHistory
import com.panomc.platform.error.AlreadyBanned
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CantBanYourself
import com.panomc.platform.error.LastAdmin
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.BannedMail
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.message.BanPlayerMessage
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.util.BanUtil
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Bans a Pano account: the website signs them out, and every server with ban integration kicks
 * them now and refuses them at login until the ban ends.
 *
 * The one place a ban is issued from, so the players page and a server's in-game roster cannot
 * drift apart on who may be banned, what gets recorded, or which servers are told.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PlayerBanService(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val tokenProvider: TokenProvider,
    private val serverManager: ServerManager,
    private val mailManager: MailManager
) {
    /**
     * Bans the account called [username] on behalf of [issuerId].
     *
     * [reason] is at most [MAX_REASON_LENGTH] characters; [bannedUntil] is an epoch-millisecond
     * end, or null for a permanent ban. [issuerIsAdmin] is whether the issuer holds `*`, which is
     * what banning another admin takes.
     */
    suspend fun ban(
        username: String,
        issuerId: Long,
        issuerIsAdmin: Boolean,
        reason: String?,
        bannedUntil: Long?,
        sendNotification: Boolean,
        sqlClient: SqlClient
    ) {
        if (!reason.isNullOrBlank() && reason.length > MAX_REASON_LENGTH) {
            throw BadRequest()
        }

        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotExists()

        if (userId == issuerId) {
            throw CantBanYourself()
        }

        val player = databaseManager.userDao.getById(userId, sqlClient) ?: throw NotExists()

        if (BanUtil.isBanned(player)) {
            throw AlreadyBanned()
        }

        if (authProvider.isUserAdmin(userId)) {
            if (!issuerIsAdmin) {
                throw NoPermission()
            }

            if (permissionManager.getUserIdsWithNode("*").size == 1) {
                throw LastAdmin()
            }
        }

        val issuerUsername = databaseManager.userDao.getUsernameFromUserId(issuerId, sqlClient) ?: throw NotExists()

        databaseManager.userDao.banPlayer(userId, reason, bannedUntil, sqlClient)
        databaseManager.banHistoryDao.add(
            BanHistory(
                userId = userId,
                reason = reason,
                emailNotified = sendNotification,
                bannedUntil = bannedUntil,
                bannedBy = issuerUsername,
                bannedBySystem = false,
                source = "PANEL"
            ), sqlClient
        )

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), AuthenticationTokenType, sqlClient)

        databaseManager.panelActivityLogDao.add(
            BannedPlayerLog(
                issuerId,
                issuerUsername,
                player.username,
                reason ?: "unknown",
                bannedUntil ?: 0L,
                (bannedUntil ?: 0L) == 0L,
                false
            ), sqlClient
        )

        databaseManager.serverPlayerDao.getByUsername(player.username, sqlClient).forEach { serverPlayer ->
            val server = serverManager.connectedServers.keys.firstOrNull { it.id == serverPlayer.serverId } ?: return@forEach

            serverManager.sendMessage(BanPlayerMessage(player.username, player.localeCode, reason, bannedUntil), server)
        }

        if (sendNotification) {
            mailManager.sendMail(sqlClient, userId, BannedMail(player.username, reason, bannedUntil))
        }
    }

    companion object {
        /** What the ban reason column holds. */
        const val MAX_REASON_LENGTH = 255
    }
}
