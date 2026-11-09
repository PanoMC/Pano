package com.panomc.platform.server.schedule

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.auth.panel.permission.ManageServerPowerPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.error.CommandDenied
import com.panomc.platform.server.console.CommandPolicy
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Makes a schedule only able to do what the person saving, enabling or running it may do by hand.
 *
 * Managing schedules alone is not enough: a COMMAND step needs console access (and passes that
 * person's `denyCommands` policy, like a command typed into the console), a POWER step needs power
 * access and a BACKUP step needs backup access, all for that server. The checks run on create and
 * update, and again on enable and run, so a schedule written by someone with more rights cannot be
 * fired by someone with fewer.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ScheduleTaskGuard(
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager
) {
    suspend fun requireAllowed(
        context: RoutingContext,
        serverId: Long,
        steps: List<Pair<ScheduleTaskKind, String?>>
    ) {
        val kinds = steps.map { it.first }.toSet()

        if (ScheduleTaskKind.COMMAND in kinds) {
            authProvider.requirePermission(ManageServerConsolePermission(), context, serverId)
        }
        if (ScheduleTaskKind.POWER in kinds) {
            authProvider.requirePermission(ManageServerPowerPermission(), context, serverId)
        }
        if (ScheduleTaskKind.BACKUP in kinds) {
            authProvider.requirePermission(ManageServerBackupsPermission(), context, serverId)
        }

        val commands = steps.filter { it.first == ScheduleTaskKind.COMMAND }.mapNotNull { it.second }
        if (commands.isEmpty()) return

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // Same rule as the console: admins bypass, everyone else gets the union of the deny lists
        // of every console grant that applies to them on this server.
        if (context.get<Boolean>("isAdmin") == true || authProvider.isUserAdmin(userId)) {
            return
        }

        val patterns = POLICY_NODES
            .flatMap { permissionManager.getApplicableNodes(userId, it, serverId) }
            .distinctBy { it.id }
            .flatMap { readPatterns(it.context.getValue(CommandPolicy.CONTEXT_KEY)) }

        commands.forEach { command ->
            val denied = CommandPolicy.deniedPattern(patterns, command) ?: return@forEach
            throw CommandDenied(extras = mapOf("pattern" to denied))
        }
    }

    suspend fun requireAllowed(context: RoutingContext, serverId: Long, definition: ScheduleDefinition) =
        requireAllowed(context, serverId, definition.tasks.map { it.kind to it.payload.getString("command") })

    private fun readPatterns(value: Any?): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { it as? String }
        is String -> value.split(",")
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private val POLICY_NODES = listOf(
            ManageServerConsolePermission().toString(),
            ManageServersPermission().toString()
        )
    }
}
