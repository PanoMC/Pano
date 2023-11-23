package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.sqlclient.SqlClient

abstract class PermissionGroupPermsDao : Dao<PermissionGroupPerms>(PermissionGroupPerms::class.java) {
    abstract suspend fun getPermissionGroupPerms(
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    abstract suspend fun getPermissionGroupPermsByPermissionId(
        permissionId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    abstract suspend fun getByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms>

    abstract suspend fun countPermissionsByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun doesPermissionGroupHavePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun addPermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun removePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun removePermissionGroup(
        permissionGroupId: Long,
        sqlClient: SqlClient
    )
}