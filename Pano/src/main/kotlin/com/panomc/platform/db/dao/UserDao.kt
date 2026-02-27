package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.PlayerStatus
import io.vertx.sqlclient.SqlClient

abstract class UserDao : Dao<User>(User::class.java) {

    abstract suspend fun add(
        user: User,
        hashedPassword: String?,
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

    abstract suspend fun countByStatusAndSearch(
        status: PlayerStatus,
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getAllByPageAndStatusAndSearch(
        page: Long,
        status: PlayerStatus,
        search: String,
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

    abstract suspend fun getIdsByListOfUsername(
        usernameList: List<String>,
        sqlClient: SqlClient
    ): Map<String, Long>

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

    abstract suspend fun getAllIds(
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun getAllIdsExcluding(
        excludeList: List<Long>,
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun getIdsByPage(
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun countByIds(
        ids: List<Long>,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByIdsPage(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun countByIdsAndSearch(
        ids: List<Long>,
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByIdsPageAndSearch(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        search: String,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun countExcludingIds(
        ids: List<Long>,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByPageExcludingIds(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun countExcludingIdsAndSearch(
        ids: List<Long>,
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByPageExcludingIdsAndSearch(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        search: String,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getAllByIds(
        ids: List<Long>,
        sqlClient: SqlClient
    ): List<User>

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

    abstract suspend fun setLocaleCodeById(
        localeCode: String?,
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun setLocaleCodeByLocaleCode(
        newLocaleCode: String?,
        oldLocaleCode: String?,
        sqlClient: SqlClient
    )

    abstract suspend fun getLocaleCodeById(
        id: Long,
        sqlClient: SqlClient
    ): String?

    abstract suspend fun setPasswordById(
        id: Long,
        password: String,
        sqlClient: SqlClient
    )

    abstract suspend fun isEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun banPlayer(
        userId: Long,
        banMessage: String?,
        bannedUntil: Long?,
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

    abstract suspend fun getLastUsers(
        limit: Long,
        sqlClient: SqlClient
    ): List<User>

    abstract suspend fun getLast5Register(
        sqlClient: SqlClient
    ): List<User>

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

    abstract suspend fun hasPassword(
        userId: Long,
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

    // Search users by username (case-insensitive substring), limited result count.
    abstract suspend fun searchIdsAndUsernamesByUsername(
        query: String,
        limit: Int,
        sqlClient: SqlClient
    ): List<Pair<Long, String>>

    abstract suspend fun setLinkCode(
        username: String,
        code: String,
        createdAt: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getLinkCode(
        username: String,
        sqlClient: SqlClient
    ): Pair<String?, Long?>?
}