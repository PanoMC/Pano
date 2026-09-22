package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.token.NodeAuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Authenticates the node daemon on its own socket.
 *
 * A twin of `ServerAuthProvider` rather than a shared implementation on purpose: the only thing
 * keeping a stolen Minecraft server token from opening a node connection — and with it process
 * control over a host — is that this checks [NodeAuthenticationTokenType] and nothing else.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeAuthProvider(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) {
    companion object {
        const val HEADER_PREFIX = "Bearer "
    }

    suspend fun isAuthenticated(context: RoutingContext): Boolean {
        val token = getTokenFromRoutingContext(context) ?: return false

        val sqlClient = databaseManager.getSqlClient()

        return try {
            tokenProvider.isTokenValid(token, NodeAuthenticationTokenType, sqlClient)
        } catch (_: Exception) {
            false
        }
    }

    /** The node id this request's token was issued for. Only call after [isAuthenticated]. */
    fun getNodeIdFromRoutingContext(context: RoutingContext): Long? {
        val token = getTokenFromRoutingContext(context) ?: return null

        return try {
            tokenProvider.parseToken(token).subject.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun getTokenFromRoutingContext(context: RoutingContext): String? {
        val authorizationHeader = context.request().getHeader("Authorization") ?: return null

        if (!authorizationHeader.startsWith(HEADER_PREFIX)) {
            return null
        }

        return authorizationHeader.substring(HEADER_PREFIX.length).trim().ifEmpty { null }
    }
}
