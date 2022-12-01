package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import io.vertx.sqlclient.SqlConnection

interface PermissionDao : Dao<Permission> {
    suspend fun isTherePermission(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isTherePermissionById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        permission: Permission,
        sqlConnection: SqlConnection
    )

    suspend fun getPermissionId(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getPermissionById(
        id: Long,
        sqlConnection: SqlConnection
    ): Permission?

    suspend fun getPermissions(
        sqlConnection: SqlConnection
    ): List<Permission>

    suspend fun arePermissionsExist(
        idList: List<Long>,
        sqlConnection: SqlConnection
    ): Boolean
}