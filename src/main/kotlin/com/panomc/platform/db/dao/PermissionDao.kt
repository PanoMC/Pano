package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import io.vertx.sqlclient.SqlClient

interface PermissionDao : Dao<Permission> {
    suspend fun isTherePermission(
        permission: Permission,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isTherePermissionById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun add(
        permission: Permission,
        sqlClient: SqlClient
    )

    suspend fun getPermissionId(
        permission: Permission,
        sqlClient: SqlClient
    ): Long

    suspend fun getPermissionById(
        id: Long,
        sqlClient: SqlClient
    ): Permission?

    suspend fun getPermissions(
        sqlClient: SqlClient
    ): List<Permission>

    suspend fun arePermissionsExist(
        idList: List<Long>,
        sqlClient: SqlClient
    ): Boolean
}