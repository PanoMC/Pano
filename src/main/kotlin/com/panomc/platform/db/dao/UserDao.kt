package com.panomc.platform.db.dao

import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.PlayerStatus
import io.vertx.sqlclient.SqlClient

interface UserDao : Dao<User> {

    suspend fun add(
        user: User,
        hashedPassword: String,
        sqlClient: SqlClient,
        isSetup: Boolean
    ): Long

    suspend fun isEmailExists(
        email: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getUserIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long?

    suspend fun getPermissionGroupIdFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long?

    suspend fun getPermissionGroupIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long?

    suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun count(sqlClient: SqlClient): Long

    suspend fun countOfRegisterByPeriod(dashboardPeriodType: DashboardPeriodType, sqlClient: SqlClient): Long

    suspend fun getRegisterDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long>

    suspend fun getUsernameFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    suspend fun getById(
        userId: Long,
        sqlClient: SqlClient
    ): User?

    suspend fun getByUsername(
        username: String,
        sqlClient: SqlClient
    ): User?

    suspend fun countByStatus(
        status: PlayerStatus,
        sqlClient: SqlClient
    ): Long

    suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlClient: SqlClient
    ): List<User>

    suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<User>

    suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): Long?

    suspend fun getEmailFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    suspend fun getUsernameByListOfId(
        userIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, String>

    suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun areUsernamesExists(
        usernames: List<String>,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsByUsernameOrEmail(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Long,
        limit: Long,
        sqlClient: SqlClient
    ): List<String>

    suspend fun getCountOfUsersByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    )

    suspend fun setPermissionGroupByUsername(
        permissionGroupId: Long,
        username: String,
        sqlClient: SqlClient
    )

    suspend fun setPermissionGroupByUsernames(
        permissionGroupId: Long,
        usernames: List<String>,
        sqlClient: SqlClient
    )

    suspend fun setUsernameById(
        id: Long,
        username: String,
        sqlClient: SqlClient
    )

    suspend fun setEmailById(
        id: Long,
        email: String,
        sqlClient: SqlClient
    )

    suspend fun setPasswordById(
        id: Long,
        password: String,
        sqlClient: SqlClient
    )

    suspend fun isEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isBanned(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun banPlayer(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun unbanPlayer(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun makeEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun getLastUsernames(
        limit: Long,
        sqlClient: SqlClient
    ): List<String>

    suspend fun updateLastLoginDate(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun updateLastActivityTime(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun updateLastPanelActivityTime(
        userId: Long,
        sqlClient: SqlClient
    )

    suspend fun getOnlineAdmins(
        limit: Long,
        sqlClient: SqlClient
    ): List<User>

    suspend fun getPermissionsById(
        userId: Long,
        sqlClient: SqlClient
    ): List<Permission>

    suspend fun getPermissionGroupNameById(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    suspend fun getIdsByPermission(
        panelPermission: PanelPermission,
        sqlClient: SqlClient
    ): List<Long>

    suspend fun updateEmailVerifyStatusById(
        userId: Long,
        verified: Boolean,
        sqlClient: SqlClient
    )

    suspend fun updateCanCreateTicketStatusById(
        userId: Long,
        canCreateTicket: Boolean,
        sqlClient: SqlClient
    )

    suspend fun isPasswordCorrectWithId(
        id: Long,
        hashedPassword: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun updatePendingEmailById(
        userId: Long,
        pendingEmail: String,
        sqlClient: SqlClient
    )

    suspend fun getPendingEmailById(
        id: Long,
        sqlClient: SqlClient
    ): String

    suspend fun countOfOnline(sqlClient: SqlClient): Long

    suspend fun deleteById(id: Long, sqlClient: SqlClient)
}