package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.UserDao
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
                              `email_verified` int(1) NOT NULL DEFAULT 0,
                              `banned` int(1) NOT NULL DEFAULT 0,
                              `mc_uuid` varchar(255) NOT NULL DEFAULT '',
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User Table';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        user: User,
        sqlConnection: SqlConnection,
        isSetup: Boolean
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (username, email, password, registered_ip, permission_group_id, register_date, email_verified) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    user.username,
                    user.email,
                    DigestUtils.md5Hex(user.password),
                    user.registeredIp,
                    user.permissionGroupId,
                    user.registerDate,
                    if (isSetup) 1 else 0
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
            "SELECT `id`, `username`, `email`, `password`, `registered_ip`, `permission_group_id`, `register_date`, `email_verified`, `banned` FROM `${getTablePrefix() + tableName}` where `id` = ?"

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
            "SELECT `id`, `username`, `email`, `password`, `registered_ip`, `permission_group_id`, `register_date`, `email_verified`, `banned` FROM `${getTablePrefix() + tableName}` where `username` = ?"

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
    ): List<Map<String, Any>> {
        val query =
            "SELECT id, username, email, register_date, permission_group_id, `banned` FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.HAS_PERM) "WHERE permission_group_id != ? " else if (status == PlayerStatus.BANNED) "WHERE banned = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.HAS_PERM)
            parameters.addInteger(-1)

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        val players = mutableListOf<Map<String, Any>>()

        if (rows.size() == 0) {
            return players
        }

        if (rows.size() > 0)
            rows.forEach { row ->
                players.add(
                    mapOf(
                        "id" to row.getLong(0),
                        "username" to row.getString(1),
                        "email" to row.getString(2),
                        "registerDate" to row.getLong(3),
                        "permissionGroupId" to row.getLong(4),
                        "banned" to row.getBoolean(5)
                    )
                )
            }

        return players
    }

    override suspend fun getAllByPageAndPermissionGroup(
        page: Long,
        permissionGroupId: Long,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>> {
        val query =
            "SELECT id, username, email, register_date, permission_group_id, `banned` FROM `${getTablePrefix() + tableName}` WHERE permission_group_id = ? ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(permissionGroupId)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        val players = mutableListOf<Map<String, Any>>()

        if (rows.size() > 0)
            rows.forEach { row ->
                players.add(
                    mapOf(
                        "id" to row.getLong(0),
                        "username" to row.getString(1),
                        "email" to row.getString(2),
                        "registerDate" to row.getLong(3),
                        "permissionGroupId" to row.getLong(4),
                        "banned" to row.getBoolean(5)
                    )
                )
            }

        return players
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
}