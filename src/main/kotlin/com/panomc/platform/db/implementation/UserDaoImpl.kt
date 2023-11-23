package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DBEntity.Companion.from
import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.PlayerStatus
import com.panomc.platform.util.TimeUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.apache.commons.codec.digest.DigestUtils

@Dao
class UserDaoImpl : UserDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `username` varchar(16) NOT NULL UNIQUE,
                              `email` varchar(255) NOT NULL UNIQUE,
                              `password` varchar(255) NOT NULL,
                              `permissionGroupId` bigint NOT NULL,
                              `registeredIp` varchar(255) NOT NULL,
                              `registerDate` BIGINT(20) NOT NULL,
                              `lastLoginDate` BIGINT(20) NOT NULL,
                              `emailVerified` TINYINT(1) NOT NULL DEFAULT 0,
                              `banned` TINYINT(1) NOT NULL DEFAULT 0,
                              `canCreateTicket` TINYINT(1) NOT NULL DEFAULT 1,
                              `mcUuid` varchar(255) NOT NULL DEFAULT '',
                              `lastActivityTime` BIGINT NOT NULL DEFAULT 0,
                              `lastPanelActivityTime` BIGINT NOT NULL DEFAULT 0,
                              `pendingEmail` varchar(255) NOT NULL DEFAULT '',
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User Table';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        user: User,
        hashedPassword: String,
        sqlClient: SqlClient,
        isSetup: Boolean
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (username, email, password, registeredIp, permissionGroupId, registerDate, `lastLoginDate`, `emailVerified`, `lastActivityTime`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    user.username,
                    user.email,
                    hashedPassword,
                    user.registeredIp,
                    user.permissionGroupId,
                    user.registerDate,
                    user.lastLoginDate,
                    if (isSetup) 1 else 0,
                    user.lastActivityTime
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun isEmailExists(
        email: String,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` where email = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    email
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getUserIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    username
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getPermissionGroupIdFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long? {
        val query =
            "SELECT permissionGroupId FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getPermissionGroupIdFromUsername(
        username: String,
        sqlClient: SqlClient
    ): Long? {
        val query =
            "SELECT permissionGroupId FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    username
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
    }

    override suspend fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where (`username` = ? or `email` = ?) and `password` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    usernameOrEmail,
                    usernameOrEmail,
                    DigestUtils.md5Hex(password)
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countOfRegisterByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `registerDate` > ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getRegisterDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long> {
        val query = "SELECT `registerDate` FROM `${getTablePrefix() + tableName}` WHERE `registerDate` > ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getUsernameFromUserId(
        userId: Long,
        sqlClient: SqlClient
    ): String? {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getString(0)
    }

    override suspend fun getById(
        userId: Long,
        sqlClient: SqlClient
    ): User? {
        val query =
            "SELECT `id`, `username`, `email`, `registeredIp`, `permissionGroupId`, `registerDate`, `lastLoginDate`, `emailVerified`, `banned`, `canCreateTicket`, `lastActivityTime`, `lastPanelActivityTime` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun getByUsername(
        username: String,
        sqlClient: SqlClient
    ): User? {
        val query =
            "SELECT `id`, `username`, `email`, `registeredIp`, `permissionGroupId`, `registerDate`, `lastLoginDate`, `emailVerified`, `banned`, `canCreateTicket`, `lastActivityTime`, `lastPanelActivityTime` FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun countByStatus(
        status: PlayerStatus,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.HAS_PERM) "WHERE permissionGroupId != ?" else if (status == PlayerStatus.BANNED) "WHERE banned = ?" else ""}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.HAS_PERM)
            parameters.addInteger(-1)

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlClient: SqlClient
    ): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registeredIp`, `permissionGroupId`, `registerDate`, `lastLoginDate`, `emailVerified`, `banned`, `canCreateTicket`, `lastActivityTime`, `lastPanelActivityTime` FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.HAS_PERM) "WHERE `permissionGroupId` != ? " else if (status == PlayerStatus.BANNED) "WHERE `banned` = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.HAS_PERM)
            parameters.addInteger(-1)

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registeredIp`, `permissionGroupId`, `registerDate`, `lastLoginDate`, `emailVerified`, `banned`, `canCreateTicket`, `lastActivityTime`, `lastPanelActivityTime` FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ? ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(permissionGroupId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): Long? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ? or email = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getEmailFromUserId(userId: Long, sqlClient: SqlClient): String? {
        val query =
            "SELECT `email` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getString(0)
    }

    override suspend fun getUsernameByListOfId(
        userIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, String> {
        var listText = ""

        userIdList.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT id, username FROM `${getTablePrefix() + tableName}` where id IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        val listOfUsers = mutableMapOf<Long, String>()

        rows.forEach { row ->
            listOfUsers[row.getLong(0)] = row.getString(1)
        }

        return listOfUsers
    }

    override suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun areUsernamesExists(usernames: List<String>, sqlClient: SqlClient): Boolean {
        var listText = ""

        usernames.forEach { username ->
            if (listText == "")
                listText = "'$username'"
            else
                listText += ", '$username'"
        }

        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0) == usernames.size.toLong()
    }

    override suspend fun existsByUsernameOrEmail(usernameOrEmail: String, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ? or `email` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Long,
        limit: Long,
        sqlClient: SqlClient
    ): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ? ${if (limit == -1L) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupId))
            .await()

        val listOfUsernames = mutableListOf<String>()

        rows.forEach { row ->
            listOfUsernames.add(row.getString(0))
        }

        return listOfUsernames
    }

    override suspend fun getCountOfUsersByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permissionGroupId` = ? WHERE `permissionGroupId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    -1,
                    permissionGroupId
                )
            )
            .await()
    }

    override suspend fun setPermissionGroupByUsername(
        permissionGroupId: Long,
        username: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permissionGroupId` = ? WHERE `username` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId,
                    username
                )
            )
            .await()
    }

    override suspend fun setPermissionGroupByUsernames(
        permissionGroupId: Long,
        usernames: List<String>,
        sqlClient: SqlClient
    ) {
        var listText = ""

        usernames.forEach { username ->
            if (listText == "")
                listText = "'$username'"
            else
                listText += ", '$username'"
        }

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permissionGroupId` = ? WHERE `username` IN ($listText)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId
                )
            )
            .await()
    }

    override suspend fun setUsernameById(
        id: Long,
        username: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `username` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    username,
                    id
                )
            )
            .await()
    }

    override suspend fun setEmailById(
        id: Long,
        email: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `email` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    email,
                    id
                )
            )
            .await()
    }

    override suspend fun setPasswordById(
        id: Long,
        password: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `password` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    DigestUtils.md5Hex(password),
                    id
                )
            )
            .await()
    }

    override suspend fun isEmailVerifiedById(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `emailVerified` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    1
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun isBanned(userId: Long, sqlClient: SqlClient): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `banned` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    1
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun banPlayer(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    1,
                    userId
                )
            )
            .await()
    }

    override suspend fun unbanPlayer(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    0,
                    userId
                )
            )
            .await()
    }

    override suspend fun makeEmailVerifiedById(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `emailVerified` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    1,
                    userId
                )
            )
            .await()
    }

    override suspend fun getLastUsernames(limit: Long, sqlClient: SqlClient): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` ORDER BY `id` DESC ${if (limit == -1L) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        val usernames = mutableListOf<String>()

        rows.forEach { row ->
            usernames.add(row.getString(0))
        }

        return usernames
    }

    override suspend fun updateLastLoginDate(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `lastLoginDate` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun updateLastActivityTime(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `lastActivityTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun updateLastPanelActivityTime(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `lastPanelActivityTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun getOnlineAdmins(limit: Long, sqlClient: SqlClient): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registeredIp`, `permissionGroupId`, `registerDate`, `lastLoginDate`, `emailVerified`, `banned`, `canCreateTicket`, `lastActivityTime`, `lastPanelActivityTime` FROM `${getTablePrefix() + tableName}` WHERE `lastPanelActivityTime` > ? ${if (limit == -1L) "" else "LIMIT $limit"}"

        val fiveMinutesAgoInMillis = System.currentTimeMillis() - 5 * 60 * 1000

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(fiveMinutesAgoInMillis)
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun getPermissionsById(userId: Long, sqlClient: SqlClient): List<Permission> {
        val query = """SELECT p.id, p.name, p.iconName
                    FROM `${getTablePrefix() + tableName}` u
                    JOIN `${getTablePrefix()}permission_group` p_group ON u.permissionGroupId = p_group.id
                    JOIN `${getTablePrefix()}permission_group_perms` p_group_perms ON p_group.id = p_group_perms.permissionGroupId
                    JOIN `${getTablePrefix()}permission` p ON p_group_perms.permissionId = p.id
                    WHERE u.id = ?"""

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(userId)
            )
            .await()

        return Permission::class.java.from(rows)
    }

    override suspend fun getPermissionGroupNameById(userId: Long, sqlClient: SqlClient): String? {
        val query = """SELECT p_group.name
                    FROM `${getTablePrefix() + tableName}` u
                    JOIN `${getTablePrefix()}permission_group` p_group ON u.permissionGroupId = p_group.id
                    WHERE u.id = ?"""

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(userId)
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getString(0)
    }

    override suspend fun getIdsByPermission(
        panelPermission: PanelPermission,
        sqlClient: SqlClient
    ): List<Long> {
        val query = """SELECT u.id
                        FROM `${getTablePrefix() + tableName}` u
                        JOIN `${getTablePrefix()}permission_group` p_group ON u.permissionGroupId = p_group.id
                        JOIN `${getTablePrefix()}permission_group_perms` p_group_perms ON p_group.id = p_group_perms.permissionGroupId
                        JOIN `${getTablePrefix()}permission` p ON p_group_perms.permissionId = p.id
                        WHERE p.name = ?"""

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(panelPermission.toString())
            )
            .await()

        val listOfUsernames = mutableListOf<Long>()

        rows.forEach { row ->
            listOfUsernames.add(row.getLong(0))
        }

        return listOfUsernames
    }

    override suspend fun updateEmailVerifyStatusById(userId: Long, verified: Boolean, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `emailVerified` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    if (verified) 1 else 0,
                    userId
                )
            )
            .await()
    }

    override suspend fun updateCanCreateTicketStatusById(
        userId: Long,
        canCreateTicket: Boolean,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `canCreateTicket` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    if (canCreateTicket) 1 else 0,
                    userId
                )
            )
            .await()
    }

    override suspend fun isPasswordCorrectWithId(
        id: Long,
        hashedPassword: String,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where `id` = ? and `password` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id,
                    hashedPassword
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun updatePendingEmailById(
        userId: Long,
        pendingEmail: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `pendingEmail` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    pendingEmail,
                    userId
                )
            )
            .await()
    }

    override suspend fun getPendingEmailById(
        id: Long,
        sqlClient: SqlClient
    ): String {
        val query =
            "SELECT `pendingEmail` FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getString(0)
    }

    override suspend fun countOfOnline(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `lastActivityTime` > ?"

        val fiveMinutesAgoInMillis = System.currentTimeMillis() - 5 * 60 * 1000

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(fiveMinutesAgoInMillis)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()
    }
}