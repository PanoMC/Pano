package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.sqlclient.SqlClient

interface PermissionGroupDao : Dao<PermissionGroup> {
    suspend fun isThereByName(
        name: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isThere(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isThereById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun add(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    ): Long

    suspend fun getPermissionGroupById(
        id: Long,
        sqlClient: SqlClient
    ): PermissionGroup?

    suspend fun getPermissionGroupIdByName(
        name: String,
        sqlClient: SqlClient
    ): Long?

    suspend fun getPermissionGroups(
        sqlClient: SqlClient
    ): List<PermissionGroup>

    suspend fun getPermissionGroupsByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<PermissionGroup>

    suspend fun countPermissionGroups(
        sqlClient: SqlClient
    ): Long

    suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun update(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    )
}