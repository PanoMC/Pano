package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

/**
 * Full permissions snapshot payload coming from a connected Minecraft server.
 *
 * The payload is an object (map) which must contain:
 * - groups: []
 * - tracks: []
 * - nodes: []
 *
 * (Compatible with the panel snapshot structure.)
 */
data class SavePermissionsSnapshotEventRequest(
    val snapshot: Map<String, Any?>
) : ServerEventRequest()


