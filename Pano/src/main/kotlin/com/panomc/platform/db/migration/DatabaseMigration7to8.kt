package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration7to8 : DatabaseMigration(7, 8, "Add manage translations permission.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addManageTranslationsPermission(),
    )

    private fun addManageTranslationsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query =
                "INSERT INTO `${getTablePrefix()}permission` (`name`, `iconName`) VALUES (?, ?);"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        "MANAGE_TRANSLATIONS",
                        "fa-language"
                    )
                )
                .coAwait()
        }
}