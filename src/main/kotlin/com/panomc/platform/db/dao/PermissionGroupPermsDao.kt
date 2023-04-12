package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.sqlclient.SqlClient

interface PermissionGroupPermsDao : Dao<PermissionGroupPerms> {
    suspend fun getPermissionGroupPerms(
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    suspend fun getPermissionGroupPermsByPermissionId(
        permissionId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    suspend fun getByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    suspend fun countPermissionsByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun doesPermissionGroupHavePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun addPermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    )

    suspend fun removePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    )

    suspend fun removePermissionGroup(
        permissionGroupId: Long,
        sqlClient: SqlClient
    )
}