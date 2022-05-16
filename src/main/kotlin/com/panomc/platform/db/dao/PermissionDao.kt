package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PermissionDao : Dao<Permission> {
    suspend fun isTherePermission(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isTherePermissionByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        permission: Permission,
        sqlConnection: SqlConnection
    )

    suspend fun getPermissionID(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getPermissionByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Permission?

    suspend fun getPermissions(
        sqlConnection: SqlConnection
    ): List<Permission>
}