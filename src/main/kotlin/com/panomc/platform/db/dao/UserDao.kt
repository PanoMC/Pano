package com.panomc.platform.db.dao

import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.PlayerStatus
import io.vertx.sqlclient.SqlClient

abstract class UserDao : Dao<User>(User::class.java) {

    abstract suspend fun add(
        user: User,
        hashedPassword: String,
        sqlClient: SqlClient,
        isSetup: Boolean
    ): Long

    abstract suspend fun isEmailExists(
        email: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getUserIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun getPermissionGroupIdFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun getPermissionGroupIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun count(sqlClient: SqlClient): Long

    abstract suspend fun countOfRegisterByPeriod(dashboardPeriodType: DashboardPeriodType, sqlClient: SqlClient): Long

    abstract suspend fun getRegisterDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun getUsernameFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    abstract suspend fun getById(
        userId: Long,
        sqlClient: SqlClient
    ): User?

    abstract suspend fun getByUsername(
        username: String,
        sqlClient: SqlClient
    ): User?

    abstract suspend fun countByStatus(
        status: PlayerStatus,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): Long?

    abstract suspend fun getEmailFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    abstract suspend fun getUsernameByListOfId(
        userIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, String>

    abstract suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun areUsernamesExists(
        usernames: List<String>,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByUsernameOrEmail(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Long,
        limit: Long,
        sqlClient: SqlClient
    ): List<String>

    abstract suspend fun getCountOfUsersByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun setPermissionGroupByUsername(
        permissionGroupId: Long,
        username: String,
        sqlClient: SqlClient
    )

    abstract suspend fun setPermissionGroupByUsernames(
        permissionGroupId: Long,
        usernames: List<String>,
        sqlClient: SqlClient
    )

    abstract suspend fun setUsernameById(
        id: Long,
        username: String,
        sqlClient: SqlClient
    )

    abstract suspend fun setEmailById(
        id: Long,
        email: String,
        sqlClient: SqlClient
    )

    abstract suspend fun setPasswordById(
        id: Long,
        password: String,
        sqlClient: SqlClient
    )

    abstract suspend fun isEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isBanned(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun banPlayer(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun unbanPlayer(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun makeEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getLastUsernames(
        limit: Long,
        sqlClient: SqlClient
    ): List<String>

    abstract suspend fun updateLastLoginDate(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateLastActivityTime(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateLastPanelActivityTime(
        userId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getOnlineAdmins(
        limit: Long,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getPermissionsById(
        userId: Long,
        sqlClient: SqlClient
    ): List<Permission>

    abstract suspend fun getPermissionGroupNameById(
        userId: Long,
        sqlClient: SqlClient
    ): String?

    abstract suspend fun getIdsByPermission(
        panelPermission: PanelPermission,
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun updateEmailVerifyStatusById(
        userId: Long,
        verified: Boolean,
        sqlClient: SqlClient
    )

    abstract suspend fun updateCanCreateTicketStatusById(
        userId: Long,
        canCreateTicket: Boolean,
        sqlClient: SqlClient
    )

    abstract suspend fun isPasswordCorrectWithId(
        id: Long,
        hashedPassword: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun updatePendingEmailById(
        userId: Long,
        pendingEmail: String,
        sqlClient: SqlClient
    )

    abstract suspend fun getPendingEmailById(
        id: Long,
        sqlClient: SqlClient
    ): String

    abstract suspend fun countOfOnline(sqlClient: SqlClient): Long

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
}