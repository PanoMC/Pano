package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.sqlclient.SqlClient

abstract class PermissionGroupDao : Dao<PermissionGroup>(PermissionGroup::class.java) {
    abstract suspend fun isThereByName(
        name: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isThere(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isThereById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun add(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getPermissionGroupById(
        id: Long,
        sqlClient: SqlClient
    ): PermissionGroup?

    abstract suspend fun getPermissionGroupIdByName(
        name: String,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun getPermissionGroups(
        sqlClient: SqlClient
    ): List<PermissionGroup>

    abstract suspend fun getPermissionGroupsByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<PermissionGroup>

    abstract suspend fun countPermissionGroups(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    )
}