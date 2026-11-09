package com.panomc.platform.token

import io.vertx.sqlclient.SqlClient

/**
 * Tokens of a Minecraft server or a pano-node use the bare server / node id as subject, the same numbers user ids
 * use. A clean-up of a user's tokens therefore never goes by subject alone.
 */
object UserTokens {
    private val machineTypes: Set<TokenType> = setOf(ServerAuthenticationTokenType, NodeAuthenticationTokenType)

    fun isMachineType(type: TokenType) = machineTypes.any { it.getName() == type.getName() }

    /** Removes every token of user [userId] (sessions, mail links, ...), leaving server and node tokens alone. */
    suspend fun invalidateAll(
        tokenProvider: TokenProvider,
        tokenTypeRegistry: TokenTypeRegistry,
        userId: Long,
        sqlClient: SqlClient
    ) {
        tokenTypeRegistry.getAll()
            .filterNot { isMachineType(it) }
            .forEach { tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), it, sqlClient) }
    }
}
