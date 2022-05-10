package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface PermissionDao : Dao<Permission> {
    fun isTherePermission(
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (isTherePermission: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isTherePermissionByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (isTherePermission: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun add(
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionID(
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (permissionID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (permission: Permission?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissions(
        sqlConnection: SqlConnection,
        handler: (permissions: List<Permission>?, asyncResult: AsyncResult<*>) -> Unit
    )
}