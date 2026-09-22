package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.dto.ServerPluginData
import com.panomc.platform.server.event.request.InstalledPluginsEventRequest

/**
 * Receives the installed plugin list of a connected server (`INSTALLED_PLUGINS`).
 *
 * The list is kept in memory only. It describes what is on that server's disk right now, so it is
 * worthless the moment the server disconnects and there is nothing to gain from storing it.
 */
@Event
class InstalledPluginsEvent(
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : ServerEvent<InstalledPluginsEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: InstalledPluginsEventRequest, server: Server): ServerEventResponse? {
        val plugins = (request.plugins ?: emptyList())
            .take(MAX_PLUGINS)
            .mapNotNull { sanitize(it) }

        serverManager.setInstalledPlugins(server.id, plugins)

        panelRealtimeHub.pushInstalledPlugins(server.id, plugins)

        return null
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(plugin: ServerPluginData): ServerPluginData? {
        val name = (if (plugin.name == null) "" else plugin.name).trim()

        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            return null
        }

        return ServerPluginData(
            name = name,
            version = plugin.version?.take(MAX_FIELD_LENGTH),
            authors = plugin.authors?.take(MAX_AUTHORS)?.map { it.take(MAX_FIELD_LENGTH) },
            description = plugin.description?.take(MAX_DESCRIPTION_LENGTH),
            enabled = plugin.enabled,
            file = plugin.file?.take(MAX_FIELD_LENGTH)
        )
    }

    companion object {
        private const val MAX_PLUGINS = 1000
        private const val MAX_NAME_LENGTH = 128
        private const val MAX_FIELD_LENGTH = 128
        private const val MAX_AUTHORS = 32
        private const val MAX_DESCRIPTION_LENGTH = 512
    }
}
