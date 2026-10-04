package com.panomc.platform.auth.panel

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.auth.panel.permission.ManageServerPlayersPermission
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.auth.panel.permission.ManageServerPowerPermission
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.auth.panel.permission.ManageServerStartupPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NoPermission
import io.vertx.ext.web.RoutingContext

/**
 * Who may *see* a server: anyone who may do at least one thing on it.
 *
 * The read endpoints the panel builds a server's pages from (the list, the row, its overview and
 * vitals) used to ask for `manage.servers` alone, so somebody given only the console of one server
 * could send it commands through the API and had no page to do it from. Seeing a server is not a
 * permission of its own; it follows from holding any of the per-server ones, globally or scoped to
 * that server.
 */
object ServerVisibility {
    private fun permissions() = arrayOf(
        ManageServersPermission(),
        ManageServerConsolePermission(),
        ManageServerPlayersPermission(),
        ManageServerFilesPermission(),
        ManageServerPluginsPermission(),
        ManageServerBackupsPermission(),
        ManageServerSchedulesPermission(),
        ManageServerPowerPermission(),
        ManageServerStartupPermission()
    )

    suspend fun canSee(authProvider: AuthProvider, context: RoutingContext, serverId: Long): Boolean =
        permissions().any { authProvider.hasPermission(it, context, serverId) }

    suspend fun requireCanSee(authProvider: AuthProvider, context: RoutingContext, serverId: Long) {
        if (!canSee(authProvider, context, serverId)) {
            throw NoPermission()
        }
    }

    /**
     * The [servers] this user may see.
     *
     * Nothing held at all is a refusal rather than an empty list -- an empty answer would tell
     * somebody with no permission that there simply are no servers. Somebody who manages servers
     * globally gets an honest empty list when there are none.
     */
    suspend fun visible(authProvider: AuthProvider, context: RoutingContext, servers: List<Server>): List<Server> {
        if (authProvider.hasPermission(ManageServersPermission(), context)) {
            return servers
        }

        val allowed = servers.filter { canSee(authProvider, context, it.id) }

        if (allowed.isEmpty()) {
            throw NoPermission()
        }

        return allowed
    }
}
