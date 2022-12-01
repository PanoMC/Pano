package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.sqlclient.SqlConnection

interface PermissionGroupDao : Dao<PermissionGroup> {
    suspend fun isThereByName(
        name: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isThere(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isThereById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getPermissionGroupById(
        id: Long,
        sqlConnection: SqlConnection
    ): PermissionGroup?

    suspend fun getPermissionGroupIdByName(
        name: String,
        sqlConnection: SqlConnection
    ): Long?

    suspend fun getPermissionGroups(
        sqlConnection: SqlConnection
    ): List<PermissionGroup>

    suspend fun getPermissionGroupsByPage(
        page: Long,
        sqlConnection: SqlConnection
    ): List<PermissionGroup>

    suspend fun countPermissionGroups(
        sqlConnection: SqlConnection
    ): Long

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun update(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    )
}