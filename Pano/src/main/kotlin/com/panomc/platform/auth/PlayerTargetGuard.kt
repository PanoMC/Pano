package com.panomc.platform.auth

import com.panomc.platform.error.NoPermission
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Who may act on another account (change its password or e-mail, delete it, ban it, sign it out, mail it).
 *
 * Holding the players permission is not enough on its own: the issuer must also hold every permission the target
 * holds. Otherwise a moderator could take over a colleague with more rights (set their password, log in as them)
 * and get those rights. An administrator (`*`) may act on everyone.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PlayerTargetGuard(
    private val permissionManager: PermissionManager
) {
    suspend fun canManage(issuerId: Long, issuerIsAdmin: Boolean, targetId: Long): Boolean {
        if (issuerIsAdmin) {
            return true
        }

        if (issuerId == targetId) {
            return true
        }

        val targetNodes = permissionManager.getGrantedNodes(targetId, anyServer = true)

        if (targetNodes.isEmpty()) {
            return true
        }

        val issuerNodes = permissionManager.getGrantedNodes(issuerId, anyServer = true)

        return isCovered(targetNodes, issuerNodes) { node -> permissionManager.hasPermissionNode(issuerId, node) }
    }

    suspend fun requireCanManage(issuerId: Long, issuerIsAdmin: Boolean, targetId: Long) {
        if (!canManage(issuerId, issuerIsAdmin, targetId)) {
            throw NoPermission()
        }
    }

    suspend fun requireCanManage(context: RoutingContext, issuerId: Long, targetId: Long) =
        requireCanManage(issuerId, context.get<Boolean>("isAdmin") ?: false, targetId)

    companion object {
        /**
         * Whether every Pano node in [targetNodes] is also held by the issuer: listed in [issuerNodes] or granted
         * through a wildcard ([issuerHolds]). `*` is only covered by `*`. Group membership, prefix / suffix and
         * in-game nodes (anything outside `pano.`) are not rights in Pano and are left out.
         */
        suspend fun isCovered(
            targetNodes: Set<String>,
            issuerNodes: Set<String>,
            issuerHolds: suspend (String) -> Boolean
        ): Boolean {
            if ("*" in targetNodes && "*" !in issuerNodes) {
                return false
            }

            return targetNodes
                .filter { it.startsWith("pano.") }
                .all { node -> node in issuerNodes || issuerHolds(node) }
        }
    }
}
