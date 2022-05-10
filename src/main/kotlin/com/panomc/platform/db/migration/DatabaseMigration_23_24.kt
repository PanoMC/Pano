package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_23_24(databaseManager: DatabaseManager, private val webClient: WebClient) :
    DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 23
    override val SCHEME_VERSION = 24
    override val SCHEME_VERSION_INFO =
        "Add MC UUID column to users table and set users UUIDs."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            addMcUuidColumnToTicketTable(),
            setUserMcUuidFromMojangApi()
        )

    private fun addMcUuidColumnToTicketTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `mc_uuid` varchar(255) NOT NULL DEFAULT '';")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun setUserMcUuidFromMojangApi(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .preparedQuery("SELECT id, username FROM `${getTablePrefix()}user`")
                .execute { queryResult ->
                    val rows: RowSet<Row> = queryResult.result()
                    val users = mutableMapOf<Long, String>()

                    rows.forEach { row ->
                        users[row.getLong(0)] = row.getString(1)
                    }

                    fun updateUser(
                        user: Pair<Long, String>,
                        invokeHandler: (AsyncResult<*>) -> Unit,
                        response: HttpResponse<Buffer>
                    ) {
                        val query = "UPDATE `${getTablePrefix()}user` SET mc_uuid = ? WHERE id = ?"

                        sqlConnection
                            .preparedQuery(query)
                            .execute(
                                Tuple.of(
//                                    response.bodyAsJsonObject().getString("name"),
                                    response.bodyAsJsonObject().getString("id"),
                                    user.first
                                )
                            ) { queryResult ->
                                invokeHandler.invoke(queryResult)
                            }
                    }

                    fun localHandler(user: Pair<Long, String>) =
                        { invokeHandler: (AsyncResult<*>?) -> Unit ->
//                            get UUID from Mojang API
                            webClient
                                .get(443, "api.mojang.com", "/users/profiles/minecraft/${user.second}")
                                .ssl(true)
                                .send()
                                .onComplete {
                                    if (it.failed()) {
                                        println(it.cause())

                                        invokeHandler.invoke(null)

                                        return@onComplete
                                    }

                                    if (it.result().statusCode() == 204) {
                                        invokeHandler.invoke(null)

                                        return@onComplete
                                    }

                                    updateUser(user, invokeHandler, it.result())
                                }
                        }

                    val mcUuidHandlers = users.map { localHandler(it.toPair()) }

                    var currentIndex = 0

                    fun invoke() {
                        val invokeHandler: (AsyncResult<*>?) -> Unit = {
                            when {
                                it !== null && it.failed() -> handler.invoke(it)
                                currentIndex == mcUuidHandlers.lastIndex -> handler.invoke(it ?: queryResult)
                                else -> {
                                    currentIndex++

                                    invoke()
                                }
                            }
                        }

                        if (currentIndex <= mcUuidHandlers.lastIndex)
                            mcUuidHandlers[currentIndex].invoke(invokeHandler)
                    }

                    invoke()
                }
        }
}