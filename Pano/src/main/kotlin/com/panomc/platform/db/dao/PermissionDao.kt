package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import io.vertx.sqlclient.SqlClient

abstract class PermissionDao : Dao<Permission>(Permission::class.java) {
    abstract suspend fun isTherePermission(
        permission: Permission,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isTherePermissionById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun add(
        permission: Permission,
        sqlClient: SqlClient
    )

    abstract suspend fun getPermissionId(
        permission: Permission,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getPermissionById(
        id: Long,
        sqlClient: SqlClient
    ): Permission?

    abstract suspend fun getPermissions(
        sqlClient: SqlClient
    ): List<Permission>

    abstract suspend fun arePermissionsExist(
        idList: List<Long>,
        sqlClient: SqlClient
    ): Boolean
}