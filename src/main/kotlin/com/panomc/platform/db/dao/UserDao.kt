package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface UserDao : Dao<User> {

    fun add(
        user: User,
        sqlConnection: SqlConnection,
        isSetup: Boolean,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isEmailExists(
        email: String,
        sqlConnection: SqlConnection,
        handler: (isEmailExists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getUserIDFromUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (userID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionGroupIDFromUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getPermissionGroupIDFromUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getSecretKeyByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (secretKey: String?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection,
        handler: (isLoginCorrect: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun getUsernameFromUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (username: String?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (user: User?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (user: User?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (userList: List<Map<String, Any>>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAllByPageAndPermissionGroup(
        page: Int,
        permissionGroupID: Int,
        sqlConnection: SqlConnection,
        handler: (userList: List<Map<String, Any>>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getUserIDFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection,
        handler: (userID: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getUsernameByListOfID(
        userIDList: List<Int>,
        sqlConnection: SqlConnection,
        handler: (usernameList: Map<Int, String>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getUsernamesByPermissionGroupID(
        permissionGroupID: Int,
        limit: Int,
        sqlConnection: SqlConnection,
        handler: (usernameList: List<String>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getCountOfUsersByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun removePermissionGroupByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun setPermissionGroupByUsername(
        permissionGroupID: Int,
        username: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun setUsernameByID(
        id: Int,
        username: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun setEmailByID(
        id: Int,
        email: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun setPasswordByID(
        id: Int,
        password: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isEmailVerifiedByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (isVerified: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )
}