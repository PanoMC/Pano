package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.User
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
                              `id` int NOT NULL AUTO_INCREMENT,
                              `username` varchar(16) NOT NULL UNIQUE,
                              `email` varchar(255) NOT NULL UNIQUE,
                              `password` varchar(255) NOT NULL,
                              `permission_group_id` int(11) NOT NULL,
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
                    user.permissionGroupID,
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

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun getUserIDFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int? {
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

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getPermissionGroupIDFromUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Int? {
        val query =
            "SELECT permission_group_id FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getPermissionGroupIDFromUsername(
        username: String,
        sqlConnection: SqlConnection
    ): Int? {
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

        return rows.toList()[0].getInteger(0)
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

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun count(sqlConnection: SqlConnection): Int {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getUsernameFromUserID(
        userID: Int,
        sqlConnection: SqlConnection
    ): String? {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(userID))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getString(0)
    }

    override suspend fun getByID(
        userID: Int,
        sqlConnection: SqlConnection
    ): User? {
        val query =
            "SELECT `username`, `email`, `password`, `registered_ip`, `permission_group_id`, `register_date`, `email_verified`, `banned` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(userID))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return User(
            userID,
            row.getString(0),
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getInteger(4),
            row.getLong(5),
            row.getInteger(6),
            row.getInteger(7)
        )
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

        return User(
            row.getInteger(0),
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getString(4),
            row.getInteger(5),
            row.getLong(6),
            row.getInteger(7),
            row.getInteger(8)
        )
    }

    override suspend fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (pageType == 2) "WHERE permission_group_id != ?" else if (pageType == 0) "WHERE banned = ?" else ""}"

        val parameters = Tuple.tuple()

        if (pageType == 2)
            parameters.addInteger(0)

        if (pageType == 0)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>> {
        val query =
            "SELECT id, username, email, register_date, permission_group_id FROM `${getTablePrefix() + tableName}` ${if (pageType == 2) "WHERE permission_group_id != ? " else if (pageType == 0) "WHERE banned = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (pageType == 2)
            parameters.addInteger(0)

        if (pageType == 0)
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
                        "id" to row.getInteger(0),
                        "username" to row.getString(1),
                        "email" to row.getString(2),
                        "registerDate" to row.getLong(3),
                        "permissionGroupId" to row.getInteger(4)
                    )
                )
            }

        return players
    }

    override suspend fun getAllByPageAndPermissionGroup(
        page: Int,
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    ): List<Map<String, Any>> {
        val query =
            "SELECT id, username, email, register_date, permission_group_id FROM `${getTablePrefix() + tableName}` WHERE permission_group_id = ? ORDER BY `id` LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addInteger(permissionGroupID)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        val players = mutableListOf<Map<String, Any>>()

        if (rows.size() > 0)
            rows.forEach { row ->
                players.add(
                    mapOf(
                        "id" to row.getInteger(0),
                        "username" to row.getString(1),
                        "email" to row.getString(2),
                        "registerDate" to row.getLong(3),
                        "permissionGroupId" to row.getInteger(4)
                    )
                )
            }

        return players
    }

    override suspend fun getUserIDFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): Int? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ? or email = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getUsernameByListOfID(
        userIDList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, String> {
        var listText = ""

        userIDList.forEach { id ->
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

        val listOfUsers = mutableMapOf<Int, String>()

        rows.forEach { row ->
            listOfUsers[row.getInteger(0)] = row.getString(1)
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

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun getUsernamesByPermissionGroupID(
        permissionGroupID: Int,
        limit: Int,
        sqlConnection: SqlConnection
    ): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? ${if (limit == -1) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupID))
            .await()

        val listOfUsernames = mutableListOf<String>()

        rows.forEach { row ->
            listOfUsernames.add(row.getString(0))
        }

        return listOfUsernames
    }

    override suspend fun getCountOfUsersByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupID))
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun removePermissionGroupByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permission_group_id` = ? WHERE `permission_group_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    -1,
                    permissionGroupID
                )
            )
            .await()
    }

    override suspend fun setPermissionGroupByUsername(
        permissionGroupID: Int,
        username: String,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `permission_group_id` = ? WHERE `username` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupID,
                    username
                )
            )
            .await()
    }

    override suspend fun setUsernameByID(
        id: Int,
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

    override suspend fun setEmailByID(
        id: Int,
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

    override suspend fun setPasswordByID(
        id: Int,
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

    override suspend fun isEmailVerifiedByID(
        userID: Int,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `email_verified` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID,
                    1
                )
            )
            .await()

        return rows.toList()[0].getInteger(0) == 1
    }
}