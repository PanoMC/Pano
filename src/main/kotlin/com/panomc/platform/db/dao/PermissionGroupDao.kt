package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PermissionGroupDao : Dao<PermissionGroup> {
    fun isThere(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (isTherePermissionGroup: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isThereByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (isTherePermissionGroup: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun add(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionGroupByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (permissionGroup: PermissionGroup?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionGroupID(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionGroups(
        sqlConnection: SqlConnection,
        handler: (permissionGroups: List<PermissionGroup>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun update(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )
}