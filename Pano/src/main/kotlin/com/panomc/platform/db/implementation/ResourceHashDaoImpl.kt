package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ResourceHashDao
import com.panomc.platform.db.model.ResourceHash
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ResourceHashDaoImpl : ResourceHashDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `hash` text NOT NULL,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Resource hash table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        resourceHash: ResourceHash,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`hash`, `status`) " +
                    "VALUES (?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    resourceHash.hash,
                    resourceHash.status
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun byListOfHash(
        hashList: List<String>,
        sqlClient: SqlClient
    ): Map<String, ResourceHash> {
        var listText = ""

        if (hashList.isEmpty()) {
            return mapOf()
        }

        hashList.forEach { hash ->
            if (listText == "")
                listText = "'$hash'"
            else
                listText += ", '$hash'"
        }

        val query =
            "SELECT `id`, `hash`, `status` FROM `${getTablePrefix() + tableName}` where `hash` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        val listOfResourceHash = mutableMapOf<String, ResourceHash>()

        rows.forEach { row ->
            listOfResourceHash[row.getString(1)] = row.toEntity()
        }

        return listOfResourceHash
    }

    override suspend fun deleteByHash(
        hash: String,
        sqlClient: SqlClient,
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `hash` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    hash
                )
            ).coAwait()
    }
}