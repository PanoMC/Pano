package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.User
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.PlayerStatus
import com.panomc.platform.util.TimeUtil
import io.vertx.kotlin.coroutines.coAwait
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
                              `email` varchar(255) UNIQUE,
                              `password` varchar(255) NOT NULL,
                              `registeredIp` varchar(255) NOT NULL,
                              `registerDate` BIGINT(20) NOT NULL,
                              `lastLoginDate` BIGINT(20) NOT NULL,
                              `emailVerified` TINYINT(1) NOT NULL DEFAULT 0,
                              `banned` TINYINT(1) NOT NULL DEFAULT 0,
                              `banMessage` varchar(255),
                              `bannedUntil` BIGINT,
                              `canCreateTicket` TINYINT(1) NOT NULL DEFAULT 1,
                              `mcUuid` varchar(255) NOT NULL DEFAULT '',
                              `lastActivityTime` BIGINT NOT NULL DEFAULT 0,
                              `lastPanelActivityTime` BIGINT NOT NULL DEFAULT 0,
                              `pendingEmail` varchar(255) NOT NULL DEFAULT '',
                              `localeCode` varchar(10) NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User Table';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun searchIdsAndUsernamesByUsername(
        query: String,
        limit: Int,
        sqlClient: SqlClient
    ): List<Pair<Long, String>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()

        val like = "%${trimmed.lowercase()}%"
        val q = """
            SELECT `id`, `username`
            FROM `${getTablePrefix() + tableName}`
            WHERE LOWER(`username`) LIKE ?
            ORDER BY `username` ASC
            LIMIT ?
        """.trimIndent()

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(q)
            .execute(Tuple.of(like, limit))
            .coAwait()

        return rows.toList().map { it.getLong("id") to it.getString("username") }
    }

    override suspend fun add(
        user: User,
        hashedPassword: String,
        sqlClient: SqlClient,
        isSetup: Boolean
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (username, email, password, registeredIp, registerDate, `lastLoginDate`, `emailVerified`, `lastActivityTime`, `localeCode`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    user.username,
                    user.email,
                    hashedPassword,
                    user.registeredIp,
                    user.registerDate,
                    user.lastLoginDate,
                    if (isSetup) 1 else 0,
                    user.lastActivityTime,
                    user.localeCode
                )
            )
            .coAwait()

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
            .coAwait()

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
            .coAwait()

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
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

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
            .coAwait()

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
            .coAwait()

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
            .coAwait()

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
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .coAwait()

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
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .coAwait()

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
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.BANNED) "WHERE banned = ?" else ""}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPageAndStatus(
        page: Long,
        status: PlayerStatus,
        sqlClient: SqlClient
    ): List<User> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ${if (status == PlayerStatus.BANNED) "WHERE `banned` = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countByStatusAndSearch(
        status: PlayerStatus,
        search: String,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `username` LIKE ? ${if (status == PlayerStatus.BANNED) "AND `banned` = ?" else ""}"

        val parameters = Tuple.tuple()
        parameters.addString("%$search%")

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPageAndStatusAndSearch(
        page: Long,
        status: PlayerStatus,
        search: String,
        sqlClient: SqlClient
    ): List<User> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `username` LIKE ? ${if (status == PlayerStatus.BANNED) "AND `banned` = ? " else ""}ORDER BY `id` LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()
        parameters.addString("%$search%")

        if (status == PlayerStatus.BANNED)
            parameters.addInteger(1)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .coAwait()

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
            .coAwait()

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
            .coAwait()

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

        if (userIdList.isEmpty()) {
            return emptyMap()
        }

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
            .coAwait()

        val listOfUsers = mutableMapOf<Long, String>()

        rows.forEach { row ->
            listOfUsers[row.getLong(0)] = row.getString(1)
        }

        return listOfUsers
    }

    override suspend fun getIdsByListOfUsername(
        usernameList: List<String>,
        sqlClient: SqlClient
    ): Map<String, Long> {
        var listText = ""

        usernameList.forEach { username ->
            if (listText == "")
                listText = "'$username'"
            else
                listText += ", '$username'"
        }

        val query =
            "SELECT `username`, `id` FROM `${getTablePrefix() + tableName}` where `username` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        val listOfUsers = mutableMapOf<String, Long>()

        rows.forEach { row ->
            listOfUsers[row.getString(0)] = row.getLong(1)
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
            .coAwait()

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
            .coAwait()

        return rows.toList()[0].getLong(0) == usernames.size.toLong()
    }

    override suspend fun existsByUsernameOrEmail(usernameOrEmail: String, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ? or `email` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
            .coAwait()

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
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getAllIds(sqlClient: SqlClient): List<Long> {
        val query = "SELECT `id` FROM `${getTablePrefix() + tableName}` ORDER BY `id`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getAllIdsExcluding(excludeList: List<Long>, sqlClient: SqlClient): List<Long> {
        if (excludeList.isEmpty()) {
            return listOf()
        }

        var listText = ""
        excludeList.forEach { id ->
            listText = if (listText.isEmpty()) "'$id'" else "$listText, '$id'"
        }

        val query = "SELECT `id` FROM `${getTablePrefix() + tableName}` WHERE `id` NOT IN ($listText) ORDER BY `id`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getIdsByPage(
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<Long> {
        val offset = ((page - 1) * pageSize).toInt()
        val query =
            "SELECT `id` FROM `${getTablePrefix() + tableName}` ORDER BY `id` LIMIT $pageSize ${if (offset == 0) "" else "OFFSET $offset"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getAllByIds(
        ids: List<Long>,
        sqlClient: SqlClient
    ): List<User> {
        if (ids.isEmpty()) return listOf()

        var listText = ""
        ids.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` IN ($listText) ORDER BY `id`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countByIds(ids: List<Long>, sqlClient: SqlClient): Long {
        if (ids.isEmpty()) return 0

        var listText = ""
        ids.forEach { id ->
            listText = if (listText.isEmpty()) "'$id'" else "$listText, '$id'"
        }

        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `id` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByIdsPage(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<User> {
        if (ids.isEmpty()) return listOf()

        var listText = ""
        ids.forEach { id ->
            listText = if (listText.isEmpty()) "'$id'" else "$listText, '$id'"
        }

        val offset = ((page - 1) * pageSize).toInt()
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` IN ($listText) ORDER BY `id` LIMIT $pageSize ${if (offset == 0) "" else "OFFSET $offset"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countExcludingIds(ids: List<Long>, sqlClient: SqlClient): Long {
        if (ids.isEmpty()) {
            return count(sqlClient)
        }

        var listText = ""
        ids.forEach { id ->
            listText = if (listText.isEmpty()) "'$id'" else "$listText, '$id'"
        }

        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `id` NOT IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByPageExcludingIds(
        ids: List<Long>,
        page: Long,
        pageSize: Int,
        sqlClient: SqlClient
    ): List<User> {
        if (ids.isEmpty()) {
            return getAllByPageAndStatus(page, PlayerStatus.ALL, sqlClient)
        }

        var listText = ""
        ids.forEach { id ->
            listText = if (listText.isEmpty()) "'$id'" else "$listText, '$id'"
        }

        val offset = ((page - 1) * pageSize).toInt()
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` NOT IN ($listText) ORDER BY `id` LIMIT $pageSize ${if (offset == 0) "" else "OFFSET $offset"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
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
            .coAwait()
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
            .coAwait()
    }

    override suspend fun setLocaleCodeById(
        localeCode: String?,
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `localeCode` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    localeCode,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun setLocaleCodeByLocaleCode(
        newLocaleCode: String?,
        oldLocaleCode: String?,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `localeCode` = ? WHERE `localeCode` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newLocaleCode,
                    oldLocaleCode
                )
            )
            .coAwait()
    }

    override suspend fun getLocaleCodeById(
        id: Long,
        sqlClient: SqlClient
    ): String? {
        val query =
            "SELECT `localeCode` FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toList()[0].getString(0)
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
            .coAwait()
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
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun banPlayer(userId: Long, banMessage: String?, bannedUntil: Long?, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ?, `banMessage` = ?, `bannedUntil` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    1,
                    banMessage,
                    bannedUntil,
                    userId
                )
            )
            .coAwait()
    }

    override suspend fun unbanPlayer(userId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `banned` = ?, `banMessage` = ?, `bannedUntil` = ?  WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    0,
                    null,
                    null,
                    userId
                )
            )
            .coAwait()
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
            .coAwait()
    }

    override suspend fun getLastUsernames(limit: Long, sqlClient: SqlClient): List<String> {
        val query =
            "SELECT username FROM `${getTablePrefix() + tableName}` ORDER BY `id` DESC ${if (limit == -1L) "" else "LIMIT $limit"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        val usernames = mutableListOf<String>()

        rows.forEach { row ->
            usernames.add(row.getString(0))
        }

        return usernames
    }

    override suspend fun getLast5Register(
        sqlClient: SqlClient
    ): List<User> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `registerDate` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
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
            .coAwait()
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
            .coAwait()
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
            .coAwait()
    }

    override suspend fun getOnlineAdmins(limit: Long, sqlClient: SqlClient): List<User> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `lastPanelActivityTime` > ? ${if (limit == -1L) "" else "LIMIT $limit"}"

        val fiveMinutesAgoInMillis = System.currentTimeMillis() - 5 * 60 * 1000

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(fiveMinutesAgoInMillis)
            )
            .coAwait()

        return rows.toEntities()
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
            .coAwait()
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
            .coAwait()
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
            .coAwait()

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
            .coAwait()
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
            .coAwait()

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
            .coAwait()

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
            .coAwait()
    }
}