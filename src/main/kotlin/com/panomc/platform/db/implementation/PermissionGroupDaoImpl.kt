package com.panomc.platform.db.implementation

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.PermissionGroupDao
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

class PermissionGroupDaoImpl(override val tableName: String = "permission_group") : DaoImpl(), PermissionGroupDao {
    private val adminPermissionName = "admin"

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `name` varchar(32) NOT NULL UNIQUE,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Table';
                        """
                )
                .execute {
                    if (it.succeeded())
                        createAdminPermission(sqlConnection) { createAdminPermissionResult ->
                            handler.invoke(createAdminPermissionResult)
                        }
                    else
                        handler.invoke(it)
                }
        }

    override fun isThere(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (isTherePermissionGroup: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`name`) FROM `${getTablePrefix() + tableName}` where `name` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) != 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isThereByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (isTherePermissionGroup: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) != 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun add(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (name) VALUES (?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionGroupByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (permissionGroup: PermissionGroup?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `name` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(PermissionGroup(id, rows.toList()[0].getString(0)), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionGroupID(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (permissionGroupID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where `name` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionGroups(
        sqlConnection: SqlConnection,
        handler: (permissionGroups: List<PermissionGroup>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `name` FROM `${getTablePrefix() + tableName}` ORDER BY `ID` ASC"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    val permissionsGroups = rows.map { row ->
                        PermissionGroup(row.getInteger(0), row.getString(1))
                    }

                    handler.invoke(permissionsGroups, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun update(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name,
                    permissionGroup.id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    private fun createAdminPermission(
        sqlConnection: SqlConnection,
        handler: (asyncResult: AsyncResult<*>) -> Unit
    ) {
        isThere(PermissionGroup(-1, adminPermissionName), sqlConnection) { isTherePermissionGroup, asyncResult ->
            if (isTherePermissionGroup == null) {
                handler.invoke(asyncResult)

                return@isThere
            }

            if (isTherePermissionGroup) {
                handler.invoke(asyncResult)

                return@isThere
            }

            add(PermissionGroup(-1, adminPermissionName), sqlConnection) { _, asyncResultAdd ->
                handler.invoke(asyncResultAdd)
            }
        }
    }
}