package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.User
import com.panomc.platform.util.PlayerStatus
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

    suspend fun getUserIdFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getPermissionGroupIdFromUserId(
        userId: Int,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getPermissionGroupIdFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun getUsernameFromUserId(
        userId: Int,
        sqlConnection: SqlConnection
    ): String?

    suspend fun getById(
        userId: Int,
        sqlConnection: SqlConnection
    ): User?

    suspend fun getByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): User?

    suspend fun countByStatus(
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getAllByPageAndStatus(
        page: Int,
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getAllByPageAndPermissionGroup(
        page: Int,
        permissionGroupId: Int,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Int?

    suspend fun getUsernameByListOfId(
        userIdList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, String>

    suspend fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Int,
        limit: Int,
        sqlConnection: SqlConnection
    ): List<String>

    suspend fun getCountOfUsersByPermissionGroupId(
        permissionGroupId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Int,
        sqlConnection: SqlConnection
    )

    suspend fun setPermissionGroupByUsername(
        permissionGroupId: Int,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setUsernameById(
        id: Int,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setEmailById(
        id: Int,
        email: String,
        sqlConnection: SqlConnection
    )

    suspend fun setPasswordById(
        id: Int,
        password: String,
        sqlConnection: SqlConnection
    )

    suspend fun isEmailVerifiedById(
        userId: Int,
        sqlConnection: SqlConnection
    ): Boolean
}