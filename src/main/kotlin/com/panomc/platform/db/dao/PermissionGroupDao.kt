package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PermissionGroupDao : Dao<PermissionGroup> {
    suspend fun isThere(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isThereById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    )

    suspend fun getPermissionGroupById(
        id: Int,
        sqlConnection: SqlConnection
    ): PermissionGroup?

    suspend fun getPermissionGroupId(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getPermissionGroups(
        sqlConnection: SqlConnection
    ): List<PermissionGroup>

    suspend fun deleteById(
        id: Int,
        sqlConnection: SqlConnection
    )

    suspend fun update(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    )
}