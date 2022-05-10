package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.Permission
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_25_26(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 25
    override val SCHEME_VERSION = 26
    override val SCHEME_VERSION_INFO =
        "Add access_panel permission."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            shiftIdsInPermissionTable(),
            addAccessPanelPermission(),
        )

    private fun shiftIdsInPermissionTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .preparedQuery("SELECT `id` FROM `${getTablePrefix()}permission` order by `id` desc")
                .execute { queryResult ->
                    val rows: RowSet<Row> = queryResult.result()

                    fun updatePermissionId(
                        id: Int,
                        invokeHandler: (AsyncResult<*>) -> Unit
                    ) {
                        val query = "UPDATE `${getTablePrefix()}permission` SET id = ? WHERE id = ?"

                        sqlConnection
                            .preparedQuery(query)
                            .execute(
                                Tuple.of(
                                    id + 1,
                                    id
                                )
                            ) { queryResult ->
                                invokeHandler.invoke(queryResult)
                            }
                    }

                    fun localHandler(id: Int) =
                        { invokeHandler: (AsyncResult<*>?) -> Unit ->
                            updatePermissionId(id, invokeHandler)
                        }

                    val updateIdHandlers = rows.map { localHandler(it.getInteger(0)) }

                    var currentIndex = 0

                    fun invoke() {
                        val invokeHandler: (AsyncResult<*>?) -> Unit = {
                            when {
                                it !== null && it.failed() -> handler.invoke(it)
                                currentIndex == updateIdHandlers.lastIndex -> handler.invoke(it ?: queryResult)
                                else -> {
                                    currentIndex++

                                    invoke()
                                }
                            }
                        }

                        if (currentIndex <= updateIdHandlers.lastIndex)
                            updateIdHandlers[currentIndex].invoke(invokeHandler)
                    }

                    invoke()
                }
        }

    private fun addAccessPanelPermission(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            val permission = Permission(-1, "access_panel", "fa-sign-in-alt")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`id`, `name`, `icon_name`) VALUES (?, ?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        1,
                        permission.name,
                        permission.iconName
                    )
                ) { queryResult ->
                    handler.invoke(queryResult)
                }
        }
}