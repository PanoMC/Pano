package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SchemeVersionDao
import com.panomc.platform.db.model.SchemeVersion
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class SchemeVersionDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "scheme_version"),
    SchemeVersionDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `when` timestamp not null default CURRENT_TIMESTAMP,
                              `key` varchar(255) not null,
                              `extra` varchar(255),
                              PRIMARY KEY (`key`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Database scheme version table.';
                        """
                )
                .execute {
                    if (it.succeeded())
                        getLastSchemeVersion(sqlConnection) { schemeVersion, asyncResult ->
                            if (schemeVersion == null)
                                add(
                                    sqlConnection,
                                    SchemeVersion(
                                        databaseManager.getLatestMigration().SCHEME_VERSION.toString(),
                                        databaseManager.getLatestMigration().SCHEME_VERSION_INFO.toString()
                                    )
                                ) { _, asyncResultAdd ->
                                    handler.invoke(asyncResultAdd)
                                }
                            else {
                                val databaseVersion = schemeVersion.key.toIntOrNull() ?: 0

                                if (databaseVersion == 0)
                                    add(
                                        sqlConnection,
                                        SchemeVersion(
                                            databaseManager.getLatestMigration().SCHEME_VERSION.toString(),
                                            databaseManager.getLatestMigration().SCHEME_VERSION_INFO.toString()
                                        )
                                    ) { _, asyncResultAdd ->
                                        handler.invoke(asyncResultAdd)
                                    }
                                else
                                    handler.invoke(asyncResult)
                            }
                        }
                    else
                        handler.invoke(it)
                }
        }

    override fun add(
        sqlConnection: SqlConnection,
        schemeVersion: SchemeVersion,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) =
        sqlConnection
            .preparedQuery("INSERT INTO `${getTablePrefix() + tableName}` (`key`, `extra`) VALUES (?, ?)")
            .execute(
                Tuple.of(
                    schemeVersion.key,
                    schemeVersion.extra
                )
            ) { queryResult ->
                handler.invoke(if (queryResult.succeeded()) Successful() else null, queryResult)
            }

    override fun add(schemeVersion: SchemeVersion, handler: (result: Result?) -> Unit) {
        databaseManager.createConnection { sqlConnection, _ ->
            if (sqlConnection != null) {
                add(sqlConnection, schemeVersion) { result, _ ->
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(result)
                    }
                }
            }
        }
    }

    override fun getLastSchemeVersion(
        sqlConnection: SqlConnection,
        handler: (schemeVersion: SchemeVersion?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT MAX(`key`) FROM `${getTablePrefix() + tableName}`"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.failed()) {
                    handler.invoke(null, queryResult)

                    return@execute
                }

                val rows: RowSet<Row> = queryResult.result()
                val row = rows.toList()[0]

                handler.invoke(
                    if (row.getString(0) == null) null else SchemeVersion(
                        row.getString(
                            0
                        ), null
                    ), queryResult
                )
            }
    }
}