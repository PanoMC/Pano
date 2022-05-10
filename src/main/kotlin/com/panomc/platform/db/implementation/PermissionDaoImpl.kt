package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PermissionDao
import com.panomc.platform.db.model.Permission
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PermissionDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "permission"), PermissionDao {
    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `name` varchar(128) NOT NULL UNIQUE,
                              `icon_name` varchar(128) NOT NULL DEFAULT '',
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Table';
                        """
                )
                .execute {
                    if (it.failed()) {
                        handler.invoke(it)

                        return@execute
                    }

                    val permissionAddHandlerList = listOf(
                        add(Permission(-1, "access_panel", "fa-cubes")),
                        add(Permission(-1, "manage_servers", "fa-cubes")),
                        add(Permission(-1, "manage_posts", "fa-sticky-note")),
                        add(Permission(-1, "manage_tickets", "fa-ticket-alt")),
                        add(Permission(-1, "manage_players", "fa-users")),
                        add(Permission(-1, "manage_view", "fa-palette")),
                        add(Permission(-1, "manage_addons", "fa-puzzle-piece")),
                        add(Permission(-1, "manage_platform_settings", "fa-cog"))
                    )

                    var currentIndex = 0

                    fun invoke() {
                        val localHandler: (AsyncResult<*>) -> Unit = { localHandlerResult ->
                            when {
                                localHandlerResult.failed() -> handler.invoke(localHandlerResult)
                                currentIndex == permissionAddHandlerList.lastIndex -> handler.invoke(localHandlerResult)
                                else -> {
                                    currentIndex++

                                    invoke()
                                }
                            }
                        }

                        if (currentIndex <= permissionAddHandlerList.lastIndex)
                            permissionAddHandlerList[currentIndex].invoke(sqlConnection, localHandler)
                    }

                    invoke()
                }
        }

    override fun isTherePermission(
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (isTherePermission: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`name`) FROM `${getTablePrefix() + tableName}` where `name` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) != 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isTherePermissionByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (isTherePermission: Boolean?, asyncResult: AsyncResult<*>) -> Unit
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
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `icon_name`) VALUES (?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name,
                    permission.iconName
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    private fun add(permission: Permission): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            add(permission, sqlConnection) { _, asyncResult ->
                handler.invoke(asyncResult)
            }
        }

    override fun getPermissionID(
        permission: Permission,
        sqlConnection: SqlConnection,
        handler: (permissionID: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where `name` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissionByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (permission: Permission?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `name`, `icon_name` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val permissionRow = rows.toList()[0]
                    val permission = Permission(id, permissionRow.getString(0), permissionRow.getString(1))

                    handler.invoke(permission, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPermissions(
        sqlConnection: SqlConnection,
        handler: (permissions: List<Permission>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `name`, `icon_name` FROM `${getTablePrefix() + tableName}`"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    val permissions = rows.map { row ->
                        Permission(row.getInteger(0), row.getString(1), row.getString(2))
                    }

                    handler.invoke(permissions, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }
}