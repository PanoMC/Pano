package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.sqlclient.SqlConnection

interface PermissionGroupPermsDao : Dao<PermissionGroupPerms> {
    suspend fun getPermissionGroupPerms(
        sqlConnection: SqlConnection
    ): List<PermissionGroupPerms>

    suspend fun getPermissionGroupPermsByPermissionId(
        permissionId: Long,
        sqlConnection: SqlConnection
    ): List<PermissionGroupPerms>

    suspend fun getByPermissionGroupId(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): List<PermissionGroupPerms>

    suspend fun countPermissionsByPermissionGroupId(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun doesPermissionGroupHavePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun addPermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun removePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun removePermissionGroup(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    )
}