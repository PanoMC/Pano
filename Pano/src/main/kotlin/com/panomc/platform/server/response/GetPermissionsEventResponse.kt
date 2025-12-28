package com.panomc.platform.server.response

import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.server.ServerEventResponse

data class GetPermissionsEventResponse(
    val groups: List<PermissionGroup>,
    val tracks: List<PermissionTrack>,
    val nodes: List<PermissionNode>,
    val usernameMap: Map<Long, String>
) : ServerEventResponse()