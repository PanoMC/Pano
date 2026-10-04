package com.panomc.node.files

import com.panomc.node.net.PluginScanMessage
import com.panomc.node.net.PluginToggleMessage
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * `PLUGIN_SCAN` and `PLUGIN_TOGGLE`, answered out of a server's jar directory (§2.4.17 B).
 *
 * Shaped exactly like [FileService]: a request in, a `FILE_RESULT` payload out, nothing thrown.
 * The same reason applies -- the far end is a panel page waiting on an HTTP response, and an
 * operator who names a jar that is no longer there should see a message rather than a daemon that
 * stopped answering.
 *
 * The scanning itself lives in [PluginScanner], which knows nothing about servers or messages, so
 * the jar parsing and the toggle can be tested against a directory of fixture jars.
 */
class PluginScanService(
    private val registry: ServerRegistry,
    private val logger: NodeLogger
) {
    private val scanner = PluginScanner()

    fun scan(message: PluginScanMessage): JsonObject {
        val server = registry.get(message.serverUuid)
            ?: return FileService.failure(FileService.ERROR_UNKNOWN_SERVER)

        return try {
            val directory = PluginScanner.resolveDirectory(server.directory, server.spec.software)
            val entries = JsonArray()

            scanner.scan(directory, PluginScanner.kindOf(directory)).forEach { plugin ->
                entries.add(describe(plugin))
            }

            FileService.success()
                // Which directory this came out of, because "Plugins" and "Mods" are the same page
                // with a different word on it and the panel should not have to guess which.
                .put("directory", directory.name)
                .put("plugins", entries)
        } catch (exception: Exception) {
            logger.warn("PLUGIN_SCAN on ${server.uuid} failed: ${exception.message}")

            FileService.failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun toggle(message: PluginToggleMessage): JsonObject {
        val server = registry.get(message.serverUuid)
            ?: return FileService.failure(FileService.ERROR_UNKNOWN_SERVER)

        val enabled = message.enabled ?: return FileService.failure(PluginScanner.ERROR_BAD_REQUEST)

        return try {
            val directory = PluginScanner.resolveDirectory(server.directory, server.spec.software)

            when (val result = scanner.toggle(directory, message.file, enabled)) {
                is PluginScanner.ToggleResult.Renamed -> FileService.success()
                    .put("file", result.file)
                    .put("enabled", result.enabled)
                    // Always: a jar is loaded at start and a rename cannot reach the process that
                    // already loaded it.
                    .put("restartRequired", true)

                is PluginScanner.ToggleResult.Failed -> FileService.failure(result.error)
            }
        } catch (exception: Exception) {
            logger.warn("PLUGIN_TOGGLE on ${server.uuid} failed: ${exception.message}")

            FileService.failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun describe(plugin: ScannedPlugin): JsonObject {
        val entry = JsonObject()
            .put("file", plugin.file)
            .put("name", plugin.name)
            .put("enabled", plugin.enabled)
            .put("kind", plugin.kind)

        plugin.version?.let { entry.put("version", it) }
        plugin.main?.let { entry.put("main", it) }
        plugin.api?.let { entry.put("api", it) }
        plugin.description?.let { entry.put("description", it) }

        if (plugin.authors.isNotEmpty()) {
            val authors = JsonArray()

            plugin.authors.forEach { authors.add(it) }

            entry.put("authors", authors)
        }

        return entry
    }
}
