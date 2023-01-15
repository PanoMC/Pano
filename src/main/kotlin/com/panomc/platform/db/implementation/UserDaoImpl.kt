package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.apache.commons.codec.digest.DigestUtils

@Dao
class UserDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "user"), UserDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `username` varchar(16) NOT NULL UNIQUE,
                              `email` varchar(255) NOT NULL UNIQUE,
                              `password` varchar(255) NOT NULL,
                              `permission_group_id` bigint NOT NULL,
                              `registered_ip` varchar(255) NOT NULL,
                              `register_date` BIGINT(20) NOT NULL,
                              `last_login_date` BIGINT(20) NOT NULL,
                              `email_verified` int(1) NOT NULL DEFAULT 0,
                              `banned` int(1) NOT NULL DEFAULT 0,
                              `can_create_ticket` int(1) NOT NULL DEFAULT 1,
                              `mc_uuid` varchar(255) NOT NULL DEFAULT '',
                              `last_activity_time` BIGINT NOT NULL DEFAULT 0,
                              `last_panel_activity_time` BIGINT NOT NULL DEFAULT 0,
                              `pending_email` varchar(255) NOT NULL DEFAULT '',
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
        sqlConnection: SqlConnection,
        isSetup: Boolean
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (username, email, password, registered_ip, permission_group_id, register_date, `last_login_date`, `email_verified`, `last_activity_time`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` where email = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Long? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Long? {
        val query =
            "SELECT permission_group_id FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Long? {
        val query =
            "SELECT permission_group_id FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where (`username` = ? or `email` = ?) and `password` = ?"

        val rows: RowSet<Row> = sqlConnection
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

    override suspend fun count(sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countOfRegisterByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `register_date` > ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getRegisterDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): List<Long> {
        val query = "SELECT `register_date` FROM `${getTablePrefix() + tableName}` WHERE `register_date` > ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getUsernameFromUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): String? {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): User? {
        val query =
            "SELECT `id`, `username`, `email`, `registered_ip`, `permission_group_id`, `register_date`, `last_login_date`, `email_verified`, `banned`, `can_create_ticket`, `last_activity_time`, `last_panel_activity_time` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return User.from(row)
    }

    override suspend fun getByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): User? {
        val query =
            "SELECT `id`, `username`, `email`, `registered_ip`, `permission_group_id`, `register_date`, `last_login_date`, `email_verified`, `banned`, `can_create_ticket`, `last_activity_time`, `last_panel_activity_time` FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return User.from(row)
    }

    override suspend fun countByStatus(
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.HAS_PERM) "WHERE permission_group_id != ?" else if (status == PlayerStatus.BANNED) "WHERE banned = ?" else ""}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.HAS_PERM)
            parameters.addInteger(-1)

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlConnection: SqlConnection
    ): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registered_ip`, `permission_group_id`, `register_date`, `last_login_date`, `email_verified`, `banned`, `can_create_ticket`, `last_activity_time`, `last_panel_activity_time` FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.HAS_PERM) "WHERE `permission_group_id` != ? " else if (status == PlayerStatus.BANNED) "WHERE `banned` = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.HAS_PERM)
            parameters.addInteger(-1)

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return User.from(rows)
    }

    override suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registered_ip`, `permission_group_id`, `register_date`, `last_login_date`, `email_verified`, `banned`, `can_create_ticket`, `last_activity_time`, `last_panel_activity_time` FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(permissionGroupId)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return User.from(rows)
    }

    override suspend fun getUserIdFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Long? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ? or email = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getEmailFromUserId(userId: Long, sqlConnection: SqlConnection): String? {
        val query =
            "SELECT `email` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
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

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val listOfUsers = mutableMapOf<Long, String>()

        rows.forEach { row ->
            listOfUsers[row.getLong(0)] = row.getString(1)
        }

        return listOfUsers
    }

    override suspend fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun areUsernamesExists(usernames: List<String>, sqlConnection: SqlConnection): Boolean {
        var listText = ""

        usernames.forEach { username ->
            if (listText == "")
                listText = "'$username'"
            else
                listText += ", '$username'"
        }

        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` IN ($listText)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0) == usernames.size.toLong()
    }

    override suspend fun isExistsByUsernameOrEmail(usernameOrEmail: String, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ? or `email` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getUsernamesByPermissionGroupId(
        permissionGroupId: Long,
        limit: Long,
        sqlConnection: SqlConnection
    ): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? ${if (limit == -1L) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun removePermissionGroupByPermissionGroupId(
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permission_group_id` = ? WHERE `permission_group_id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permission_group_id` = ? WHERE `username` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        var listText = ""

        usernames.forEach { username ->
            if (listText == "")
                listText = "'$username'"
            else
                listText += ", '$username'"
        }

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permission_group_id` = ? WHERE `username` IN ($listText)"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `username` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `email` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `password` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `email_verified` = ?"

        val rows: RowSet<Row> = sqlConnection
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

    override suspend fun isBanned(userId: Long, sqlConnection: SqlConnection): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `banned` = ?"

        val rows: RowSet<Row> = sqlConnection
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

    override suspend fun banPlayer(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    1,
                    userId
                )
            )
            .await()
    }

    override suspend fun unbanPlayer(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    0,
                    userId
                )
            )
            .await()
    }

    override suspend fun makeEmailVerifiedById(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `email_verified` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    1,
                    userId
                )
            )
            .await()
    }

    override suspend fun getLastUsernames(limit: Long, sqlConnection: SqlConnection): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` ORDER BY `id` DESC ${if (limit == -1L) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val usernames = mutableListOf<String>()

        rows.forEach { row ->
            usernames.add(row.getString(0))
        }

        return usernames
    }

    override suspend fun updateLastLoginDate(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `last_login_date` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun updateLastActivityTime(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `last_activity_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun updateLastPanelActivityTime(userId: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `last_panel_activity_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    userId
                )
            )
            .await()
    }

    override suspend fun getOnlineAdmins(limit: Long, sqlConnection: SqlConnection): List<User> {
        val query =
            "SELECT `id`, `username`, `email`, `registered_ip`, `permission_group_id`, `register_date`, `last_login_date`, `email_verified`, `banned`, `can_create_ticket`, `last_activity_time`, `last_panel_activity_time` FROM `${getTablePrefix() + tableName}` WHERE `last_panel_activity_time` > ? ${if (limit == -1L) "" else "LIMIT $limit"}"

        val fiveMinutesAgoInMillis = System.currentTimeMillis() - 5 * 60 * 1000

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(fiveMinutesAgoInMillis)
            )
            .await()

        return User.from(rows)
    }

    override suspend fun getPermissionsById(userId: Long, sqlConnection: SqlConnection): List<Permission> {
        val query = """SELECT p.id, p.name, p.icon_name
                    FROM `${getTablePrefix() + tableName}` u
                    JOIN `${getTablePrefix()}permission_group` p_group ON u.permission_group_id = p_group.id
                    JOIN `${getTablePrefix()}permission_group_perms` p_group_perms ON p_group.id = p_group_perms.permission_group_id
                    JOIN `${getTablePrefix()}permission` p ON p_group_perms.permission_id = p.id
                    WHERE u.id = ?"""

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(userId)
            )
            .await()

        return Permission.from(rows)
    }

    override suspend fun getPermissionGroupNameById(userId: Long, sqlConnection: SqlConnection): String? {
        val query = """SELECT p_group.name
                    FROM `${getTablePrefix() + tableName}` u
                    JOIN `${getTablePrefix()}permission_group` p_group ON u.permission_group_id = p_group.id
                    WHERE u.id = ?"""

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): List<Long> {
        val query = """SELECT u.id
                        FROM `${getTablePrefix() + tableName}` u
                        JOIN `${getTablePrefix()}permission_group` p_group ON u.permission_group_id = p_group.id
                        JOIN `${getTablePrefix()}permission_group_perms` p_group_perms ON p_group.id = p_group_perms.permission_group_id
                        JOIN `${getTablePrefix()}permission` p ON p_group_perms.permission_id = p.id
                        WHERE p.name = ?"""

        val rows: RowSet<Row> = sqlConnection
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

    override suspend fun updateEmailVerifyStatusById(userId: Long, verified: Boolean, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `email_verified` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `can_create_ticket` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where `id` = ? and `password` = ?"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `pending_email` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ): String {
        val query =
            "SELECT `pending_email` FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getString(0)
    }

    override suspend fun countOfOnline(sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `last_activity_time` > ?"

        val fiveMinutesAgoInMillis = System.currentTimeMillis() - 5 * 60 * 1000

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(fiveMinutesAgoInMillis)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun deleteById(id: Long, sqlConnection: SqlConnection) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()
    }
}