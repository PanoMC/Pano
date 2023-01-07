package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration43To44(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 43
    override val SCHEME_VERSION = 44
    override val SCHEME_VERSION_INFO =
        "Add manage_permission_groups permission."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addManagePermissionGroupsPermission(),
        )

    private fun addManagePermissionGroupsPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        "manage_permission_groups",
                        "fa-lock-open"
                    )
                )
                .await()
        }
}