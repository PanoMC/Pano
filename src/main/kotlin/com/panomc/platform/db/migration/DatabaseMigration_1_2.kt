package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_1_2(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 1
    override val SCHEME_VERSION = 2
    override val SCHEME_VERSION_INFO = ""

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            createSystemPropertyTable()
        )

    private fun createSystemPropertyTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}system_property` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `option` text NOT NULL,
                              `value` text NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System Property table.';
                        """
                )
                .execute {
                    if (it.succeeded())
                        sqlConnection
                            .preparedQuery("INSERT INTO ${getTablePrefix()}system_property (`option`, `value`) VALUES (?, ?)")
                            .execute(Tuple.of("show_getting_started", "true")) { queryResult ->
                                if (queryResult.succeeded())
                                    sqlConnection
                                        .preparedQuery("INSERT INTO ${getTablePrefix()}system_property (`option`, `value`) VALUES (?, ?)")
                                        .execute(Tuple.of("show_connect_server_info", "true")) { queryResult2 ->
                                            handler.invoke(queryResult2)
                                        }
                                else
                                    handler.invoke(queryResult)
                            }
                    else
                        handler.invoke(it)
                }
        }
}