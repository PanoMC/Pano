package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PermissionGroupPermsDao : Dao<PermissionGroupPerms> {
    suspend fun getPermissionGroupPerms(
        sqlConnection: SqlConnection
    ): List<PermissionGroupPerms>

    suspend fun doesPermissionGroupHavePermission(
        permissionGroupID: Int,
        permissionID: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun addPermission(
        permissionGroupID: Int,
        permissionID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun removePermission(
        permissionGroupID: Int,
        permissionID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun removePermissionGroup(
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    )
}