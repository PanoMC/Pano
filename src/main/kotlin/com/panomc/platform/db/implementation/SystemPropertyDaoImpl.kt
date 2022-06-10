package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SystemPropertyDao
import com.panomc.platform.db.model.SystemProperty
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class SystemPropertyDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "system_property"),
    SystemPropertyDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `option` text NOT NULL,
                          `value` text NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System Property table.';
                    """
            )
            .execute()
            .await()

        addShowGettingStartedOption(sqlConnection)
    }

    override suspend fun add(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`option`, `value`) VALUES (?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    systemProperty.option,
                    systemProperty.value
                )
            )
            .await()
    }

    override suspend fun update(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ) {
        val params = Tuple.tuple()

        params.addString(systemProperty.value)

        if (systemProperty.id == -1L)
            params.addString(systemProperty.option)
        else
            params.addLong(systemProperty.id)

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET value = ? ${if (systemProperty.id != -1L) ", option = ?" else ""} WHERE `${if (systemProperty.id == -1L) "option" else "id"}` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                params
            )
            .await()
    }


    override suspend fun isPropertyExists(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(systemProperty.option)
            )
            .await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ? and `value` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    "who_installed_user_id",
                    userId.toString()
                )
            )
            .await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun getValue(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection
    ): SystemProperty? {
        val query = "SELECT `id`, `option`, `value` FROM `${getTablePrefix() + tableName}` where `option` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    systemProperty.option
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return SystemProperty.from(row)
    }

    private suspend fun addShowGettingStartedOption(
        sqlConnection: SqlConnection
    ) {
        add(SystemProperty(option = "show_getting_started", value = "true"), sqlConnection)
    }
}