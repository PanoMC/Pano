package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.Permission
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration25To26(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 25
    override val SCHEME_VERSION = 26
    override val SCHEME_VERSION_INFO =
        "Add access_panel permission."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            shiftIdsInPermissionTable(),
            addAccessPanelPermission(),
        )

    private fun shiftIdsInPermissionTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val rows: RowSet<Row> = sqlClient
                .preparedQuery("SELECT `id` FROM `${getTablePrefix()}permission` order by `id` desc")
                .execute()
                .await()

            rows.forEach {
                val id = it.getLong(0)
                val query = "UPDATE `${getTablePrefix()}permission` SET id = ? WHERE id = ?"

                sqlClient
                    .preparedQuery(query)
                    .execute(
                        Tuple.of(
                            id + 1,
                            id
                        )
                    )
                    .await()
            }
        }

    private fun addAccessPanelPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "access_panel", iconName = "fa-sign-in-alt")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`id`, `name`, `icon_name`) VALUES (?, ?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        1,
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }
}