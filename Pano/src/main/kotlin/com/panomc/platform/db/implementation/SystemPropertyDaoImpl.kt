package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.SystemPropertyDao
import com.panomc.platform.db.model.SystemProperty
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class SystemPropertyDaoImpl : SystemPropertyDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
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

        addShowGettingStartedOption(sqlClient)
        addMainServerOption(sqlClient)
    }

    override suspend fun add(
        systemProperty: SystemProperty,
        sqlClient: SqlClient
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`option`, `value`) VALUES (?, ?)"

        sqlClient
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
        option: String,
        value: String,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `value` = ? WHERE `option` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    value,
                    option
                )
            )
            .await()
    }


    override suspend fun existsByOption(
        option: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(option)
            )
            .await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun isUserInstalledSystemByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ? and `value` = ?"

        val rows: RowSet<Row> = sqlClient
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

    override suspend fun getByOption(
        option: String,
        sqlClient: SqlClient
    ): SystemProperty? {
        val query = "SELECT `id`, `option`, `value` FROM `${getTablePrefix() + tableName}` where `option` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    option
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    private suspend fun addShowGettingStartedOption(
        sqlClient: SqlClient
    ) {
        add(SystemProperty(option = "show_getting_started", value = "true"), sqlClient)
    }

    private suspend fun addMainServerOption(
        sqlClient: SqlClient
    ) {
        add(SystemProperty(option = "main_server", value = "-1"), sqlClient)
    }
}