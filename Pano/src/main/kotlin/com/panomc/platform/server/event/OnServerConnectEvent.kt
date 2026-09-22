package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.server.ServerTimeZone
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.event.request.OnServerConnectEventRequest
import com.panomc.platform.server.schedule.ServerScheduleService
import com.panomc.platform.util.ImageValidationUtil

@Event
class OnServerConnectEvent(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverScheduleService: ServerScheduleService
) : ServerEvent<OnServerConnectEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: OnServerConnectEventRequest, server: Server): ServerEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        server.name = request.serverName
        server.motd = request.motd ?: ""
        server.host = request.host
        server.port = request.port
        server.playerCount = request.playerCount
        server.maxPlayerCount = request.maxPlayerCount
        server.type = request.serverType
        server.version = request.serverVersion
        // Validate favicon format - only allow safe raster image data URLs (no SVG)
        server.favicon = ImageValidationUtil.sanitizeFaviconDataUrl(request.favicon) ?: ""
        server.status = ServerStatus.ONLINE
        server.startTime = request.startTime

        // Plugins older than the capability handshake announce nothing at all, so they stay on the
        // legacy protocol version and are treated as having no capabilities.
        val protocolVersion = request.protocolVersion ?: ServerProtocol.LEGACY_PROTOCOL_VERSION

        server.protocolVersion = protocolVersion
        server.pluginVersion = request.pluginVersion
        server.capabilities = if (protocolVersion <= ServerProtocol.LEGACY_PROTOCOL_VERSION)
            emptyList()
        else
            // Drop ids this Pano version does not know instead of failing the handshake: a newer
            // plugin may announce capabilities that were added after this release.
            (request.capabilities ?: emptyList()).filter { ServerCapability.fromId(it) != null }

        // The game JVM's own zone, which beats the host's the node reported; an invalid one, or none
        // from an older plugin, leaves whatever was there (§2.4.25).
        server.timeZone = ServerTimeZone.fromPlugin(server.timeZone, request.timeZone)

        databaseManager.serverDao.update(server, sqlClient)

        // The capability list is only known now, so this is the first moment Pano can tell
        // whether this plugin is the one that should be running the server's schedules
        // (§2.4.17 C). A plugin that does not announce `schedules` is a no-op here.
        serverScheduleService.syncServer(server, sqlClient)

        panelRealtimeHub.notifyServerUpdated(server.id)

        return null
    }
}