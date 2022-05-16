package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.User
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface UserDao : Dao<User> {

    suspend fun add(
        user: User,
        sqlConnection: SqlConnection,
        isSetup: Boolean
    ): Long

    suspend fun isEmailExists(
        email: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getUserIDFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getPermissionGroupIDFromUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getPermissionGroupIDFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun getUsernameFromUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): String?

    suspend fun getByID(
        userID: Int,
        sqlConnection: SqlConnection
    ): User?

    suspend fun getByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): User?

    suspend fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getAllByPageAndPermissionGroup(
        page: Int,
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getUserIDFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getUsernameByListOfID(
        userIDList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, String>

    suspend fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getUsernamesByPermissionGroupID(
        permissionGroupID: Int,
        limit: Int,
        sqlConnection: SqlConnection
    ): List<String>

    suspend fun getCountOfUsersByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun removePermissionGroupByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    )

    suspend fun setPermissionGroupByUsername(
        permissionGroupID: Int,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setUsernameByID(
        id: Int,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setEmailByID(
        id: Int,
        email: String,
        sqlConnection: SqlConnection
    )

    suspend fun setPasswordByID(
        id: Int,
        password: String,
        sqlConnection: SqlConnection
    )

    suspend fun isEmailVerifiedByID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Boolean
}