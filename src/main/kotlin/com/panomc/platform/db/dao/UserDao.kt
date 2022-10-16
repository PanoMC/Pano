package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
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
    ): Long?

    suspend fun getPermissionGroupIdFromUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Long?

    suspend fun getPermissionGroupIdFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Long?

    suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun count(sqlConnection: SqlConnection): Long

    suspend fun countOfRegisterByPeriod(dashboardPeriodType: DashboardPeriodType, sqlConnection: SqlConnection): Long

    suspend fun getRegisterDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): List<Long>

    suspend fun getUsernameFromUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): String?

    suspend fun getById(
        userId: Long,
        sqlConnection: SqlConnection
    ): User?

    suspend fun getByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): User?

    suspend fun countByStatus(
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>>

    suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Long?

    suspend fun getEmailFromUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): String?

    suspend fun getUsernameByListOfId(
        userIdList: List<Long>,
        sqlConnection: SqlConnection
    ): Map<Long, String>

    suspend fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Long,
        limit: Long,
        sqlConnection: SqlConnection
    ): List<String>

    suspend fun getCountOfUsersByPermissionGroupId(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun setPermissionGroupByUsername(
        permissionGroupId: Long,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setUsernameById(
        id: Long,
        username: String,
        sqlConnection: SqlConnection
    )

    suspend fun setEmailById(
        id: Long,
        email: String,
        sqlConnection: SqlConnection
    )

    suspend fun setPasswordById(
        id: Long,
        password: String,
        sqlConnection: SqlConnection
    )

    suspend fun isEmailVerifiedById(
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isBanned(
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun banPlayer(
        userId: Long,
        sqlConnection: SqlConnection
    )

    suspend fun makeEmailVerifiedById(
        userId: Long,
        sqlConnection: SqlConnection
    )
}