package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration12to13 : DatabaseMigration(
    12,
    13,
    "Add aesKey column to server table & delete all servers"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addAesKeyColumnToServerTable(),
        deleteAllServers(),
        deleteAllPlayersInServerPlayerTable(),
        deleteServerAuthTokens(),
        updateMainServer()
    )

    private fun addAesKeyColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}server` ADD COLUMN `aesKey` TEXT NOT NULL;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun deleteAllServers(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "DELETE FROM `${getTablePrefix()}server`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun deleteAllPlayersInServerPlayerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "DELETE FROM `${getTablePrefix()}server_player`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun deleteServerAuthTokens(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "DELETE FROM `${getTablePrefix()}token` WHERE `type` = ?;"

            sqlClient
                .preparedQuery(query)
                .execute(Tuple.of("SERVER_AUTHENTICATION"))
                .coAwait()
        }

    private fun updateMainServer(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "UPDATE `${getTablePrefix()}system_property` SET `value` = ? WHERE `option` = ?;"

            sqlClient
                .preparedQuery(query)
                .execute(Tuple.of(-1, "main_server"))
                .coAwait()
        }
}