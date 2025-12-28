package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.IsPlayerRegisteredEventRequest
import com.panomc.platform.server.response.GetPermissionsEventResponse

@Event
class GetPermissionsEvent(
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager
) : ServerEvent<IsPlayerRegisteredEventRequest, GetPermissionsEventResponse>() {
    override suspend fun handle(request: IsPlayerRegisteredEventRequest, server: Server): GetPermissionsEventResponse {
        val sqlClient = databaseManager.getSqlClient()

        val groups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val tracks = databaseManager.permissionTrackDao.getAll(sqlClient)
        val nodes = databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)

        val userIds = permissionManager.getCachedUserIds()
        val usernameMap = databaseManager.userDao.getUsernameByListOfId(userIds.toList(), sqlClient)

        return GetPermissionsEventResponse(
            groups,
            tracks,
            nodes,
            usernameMap
        )
    }
}