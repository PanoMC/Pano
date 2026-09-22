package com.panomc.node.task

import com.panomc.node.console.ConsoleLevel
import com.panomc.node.net.UpdateStartupMessage
import com.panomc.node.server.ServerProperties
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import java.io.File

/**
 * Applies `UPDATE_STARTUP` to a server's stored spec and its `server.properties`.
 *
 * Two files change together and both have to survive a restart of either side, so the spec is
 * rewritten on disk immediately rather than kept in memory until the next start. A running server
 * keeps running on its old settings: restarting it under someone would be a far bigger surprise
 * than a memory change that only takes effect next time.
 */
class StartupService(
    private val registry: ServerRegistry,
    private val logger: NodeLogger
) {
    fun update(message: UpdateStartupMessage) {
        val uuid = message.serverUuid
        val spec = message.spec

        if (uuid.isNullOrBlank() || spec == null) {
            logger.warn("Ignoring an UPDATE_STARTUP with no server uuid or spec.")

            return
        }

        val server = registry.get(uuid)

        if (server == null) {
            logger.warn("Ignoring an UPDATE_STARTUP for unknown server $uuid.")

            return
        }

        val current = server.spec

        val properties = LinkedHashMap(current.properties)

        spec.properties?.forEach { (key, value) -> properties[key] = value }

        val port = spec.port?.takeIf { it in 1..65535 } ?: current.port

        if (port != current.port) {
            properties["server-port"] = port.toString()
        }

        val updated = current.copy(
            javaMajor = spec.javaMajor?.takeIf { it > 0 } ?: current.javaMajor,
            memoryMb = spec.memoryMb?.takeIf { it > 0 } ?: current.memoryMb,
            jvmArgs = spec.jvmArgs ?: current.jvmArgs,
            port = port,
            autoStart = spec.autoStart ?: current.autoStart,
            crashRestart = spec.crashRestart ?: current.crashRestart,
            properties = properties
        ).sanitised()

        server.updateSpec(updated)

        registry.writeSpec(server.directory, updated)

        ServerProperties.merge(File(server.directory, "server.properties"), properties)

        server.emit(ConsoleLevel.INFO, "Pano updated the startup settings; they apply on the next start.")

        logger.info("Updated startup settings for server $uuid.")
    }
}
