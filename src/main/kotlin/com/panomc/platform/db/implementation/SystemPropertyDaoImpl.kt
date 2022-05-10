package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SystemPropertyDao
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class SystemPropertyDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "system_property"),
    SystemPropertyDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` int NOT NULL AUTO_INCREMENT,
                          `option` text NOT NULL,
                          `value` text NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System Property table.';
                    """
                )
                .execute {
                    if (it.succeeded())
                        addShowGettingStartedOption(sqlConnection) {
                            handler.invoke(it)
                        }
                    else
                        handler.invoke(it)
                }
        }

    override fun add(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`option`, `value`) VALUES (?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    systemProperty.option,
                    systemProperty.value
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun update(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val params = Tuple.tuple()

        params.addString(systemProperty.value)

        if (systemProperty.id == -1)
            params.addString(systemProperty.option)
        else
            params.addInteger(systemProperty.id)

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET value = ? ${if (systemProperty.id != -1) ", option = ?" else ""} WHERE `${if (systemProperty.id == -1) "option" else "id"}` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                params
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }


    override fun isPropertyExists(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(systemProperty.option)
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) != 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isUserInstalledSystemByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (isUserInstalledSystem: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`value`) FROM `${getTablePrefix() + tableName}` where `option` = ? and `value` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    "who_installed_user_id",
                    userID.toString()
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) != 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getValue(
        systemProperty: SystemProperty,
        sqlConnection: SqlConnection,
        handler: (systemProperty: SystemProperty?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT `value` FROM `${getTablePrefix() + tableName}` where `option` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    systemProperty.option
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(
                        SystemProperty(
                            systemProperty.id,
                            systemProperty.option,
                            rows.toList()[0].getString(0)
                        ), queryResult
                    )
                } else
                    handler.invoke(null, queryResult)
            }
    }

    private fun addShowGettingStartedOption(
        sqlConnection: SqlConnection,
        handler: (asyncResult: AsyncResult<*>) -> Unit
    ) {
        add(SystemProperty(-1, "show_getting_started", "true"), sqlConnection) { _, asyncResult ->
            handler.invoke(asyncResult)
        }
    }
}