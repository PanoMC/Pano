package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration23To24(databaseManager: DatabaseManager, private val webClient: WebClient) :
    DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 23
    override val SCHEME_VERSION = 24
    override val SCHEME_VERSION_INFO =
        "Add MC UUID column to users table and set users UUIDs."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addMcUuidColumnToTicketTable(),
            setUserMcUuidFromMojangApi()
        )

    private fun addMcUuidColumnToTicketTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `mc_uuid` varchar(255) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun setUserMcUuidFromMojangApi(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val rows: RowSet<Row> = sqlConnection
                .preparedQuery("SELECT id, username FROM `${getTablePrefix()}user`")
                .execute()
                .await()

            val users = mutableMapOf<Long, String>()

            rows.forEach { row ->
                users[row.getLong(0)] = row.getString(1)
            }

            users.forEach { user ->
//                            get UUID from Mojang API
                val response = webClient
                    .get(443, "api.mojang.com", "/users/profiles/minecraft/${user.value}")
                    .ssl(true)
                    .send()
                    .await()

                val statusCode = response.statusCode()

                if (statusCode == 204) {
                    return@forEach
                }

                val query = "UPDATE `${getTablePrefix()}user` SET mc_uuid = ? WHERE id = ?"

                sqlConnection
                    .preparedQuery(query)
                    .execute(
                        Tuple.of(
//                                    response.bodyAsJsonObject().getString("name"),
                            response.bodyAsJsonObject().getString("id"),
                            user.key
                        )
                    )
                    .await()

            }
        }
}