package com.panomc.platform.db.entity

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.apache.commons.codec.digest.DigestUtils
import java.util.*

class UserDaoImpl(override val tableName: String = "user") : DaoImpl(), UserDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
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
                              `secret_key` text NOT NULL,
                              `public_key` text NOT NULL,
                              `register_date` BIGINT(20) NOT NULL,
                              `email_verified` int(1) NOT NULL DEFAULT 0,
                              `banned` int(1) NOT NULL DEFAULT 0,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User Table';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    override fun add(
        user: User,
        sqlConnection: SqlConnection,
        isSetup: Boolean,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (username, email, password, registered_ip, permission_group_id, secret_key, public_key, register_date, email_verified) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val key = Keys.keyPairFor(SignatureAlgorithm.RS256)

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    user.username,
                    user.email,
                    DigestUtils.md5Hex(user.password),
                    user.registeredIp,
                    user.permissionGroupID,
                    Base64.getEncoder().encodeToString(key.private.encoded),
                    Base64.getEncoder().encodeToString(key.public.encoded),
                    user.registerDate,
                    if (isSetup) 1 else 0
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else {
                    val errorCode = ErrorCode.UNKNOWN

                    handler.invoke(Error(errorCode), queryResult)
                }
            }
    }

    override fun isEmailExists(
        email: String,
        sqlConnection: SqlConnection,
        handler: (isEmailExists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` where email = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    email
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getUserIDFromUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (userID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    username
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionGroupIDFromUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT permission_group_id FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionGroupIDFromUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT permission_group_id FROM `${getTablePrefix() + tableName}` where `username` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    username
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getSecretKeyByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (secretKey: String?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT secret_key FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getString(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isLoginCorrect(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection,
        handler: (isLoginCorrect: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where (`username` = ? or `email` = ?) and `password` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    usernameOrEmail,
                    usernameOrEmail,
                    DigestUtils.md5Hex(password)
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getUsernameFromUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (username: String?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(userID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getString(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (user: User?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `username`, `email`, `password`, `registered_ip`, `permission_group_id`, `register_date`, `email_verified`, `banned` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(userID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val row = rows.toList()[0]

                    handler.invoke(
                        User(
                            userID,
                            row.getString(0),
                            row.getString(1),
                            row.getString(2),
                            row.getString(3),
                            row.getInteger(4),
                            row.getLong(5),
                            row.getInteger(6),
                            row.getInteger(7)
                        ),
                        queryResult
                    )
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (user: User?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `username`, `email`, `password`, `registered_ip`, `permission_group_id`, `register_date`, `email_verified`, `banned` FROM `${getTablePrefix() + tableName}` where `username` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(username)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val row = rows.toList()[0]

                    handler.invoke(
                        User(
                            row.getInteger(0),
                            row.getString(1),
                            row.getString(2),
                            row.getString(3),
                            row.getString(4),
                            row.getInteger(5),
                            row.getLong(6),
                            row.getInteger(7),
                            row.getInteger(8)
                        ),
                        queryResult
                    )
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (pageType == 2) "WHERE permission_group_id != ?" else if (pageType == 0) "WHERE banned = ?" else ""}"

        val parameters = Tuple.tuple()

        if (pageType == 2)
            parameters.addInteger(0)

        if (pageType == 0)
            parameters.addInteger(1)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (userList: List<Map<String, Any>>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, username, email, register_date, permission_group_id FROM `${getTablePrefix() + tableName}` ${if (pageType == 2) "WHERE permission_group_id != ? " else if (pageType == 0) "WHERE banned = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (pageType == 2)
            parameters.addInteger(0)

        if (pageType == 0)
            parameters.addInteger(1)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val players = mutableListOf<Map<String, Any>>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            players.add(
                                mapOf(
                                    "id" to row.getInteger(0),
                                    "username" to row.getString(1),
                                    "email" to row.getString(2),
                                    "register_date" to row.getLong(3),
                                    "permission_group_id" to row.getInteger(4)
                                )
                            )
                        }

                    handler.invoke(players, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getUserIDFromUsernameOrEmail(
        usernameOrEmail: String,
        sqlConnection: SqlConnection,
        handler: (userID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where username = ? or email = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getUsernameByListOfID(
        userIDList: List<Int>,
        sqlConnection: SqlConnection,
        handler: (usernameList: Map<Int, String>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        var listText = ""

        userIDList.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT id, username FROM `${getTablePrefix() + tableName}` where id IN ($listText)"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val listOfUsers = mutableMapOf<Int, String>()

                    rows.forEach { row ->
                        listOfUsers[row.getInteger(0)] = row.getString(1)
                    }

                    handler.invoke(listOfUsers, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isExistsByUsername(
        username: String,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(username)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getUsernamesByPermissionGroupID(
        permissionGroupID: Int,
        limit: Int,
        sqlConnection: SqlConnection,
        handler: (usernameList: List<String>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? ${if (limit == -1) "" else "LIMIT $limit"}"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val listOfUsernames = mutableListOf<String>()

                    rows.forEach { row ->
                        listOfUsernames.add(row.getString(0))
                    }

                    handler.invoke(listOfUsernames, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getCountOfUsersByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun removePermissionGroupByPermissionGroupID(
        permissionGroupID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun setPermissionGroupByUsername(
        permissionGroupID: Int,
        username: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun setUsernameByID(
        id: Int,
        username: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun setEmailByID(
        id: Int,
        email: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun setPasswordByID(
        id: Int,
        password: String,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isEmailVerifiedByID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (isVerified: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(email) FROM `${getTablePrefix() + tableName}` WHERE `id` = ? and `email_verified` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID,
                    1
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }
}