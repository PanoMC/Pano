package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.AddonHashDao
import com.panomc.platform.db.model.AddonHash
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class AddonHashDaoImpl : AddonHashDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `hash` text NOT NULL,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Addon hash table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        addonHash: AddonHash,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`hash`, `status`) " +
                    "VALUES (?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    addonHash.hash,
                    addonHash.status
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun byListOfHash(
        hashList: List<String>,
        sqlClient: SqlClient
    ): Map<String, AddonHash> {
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
            .await()

        val listOfAddonHash = mutableMapOf<String, AddonHash>()

        rows.forEach { row ->
            listOfAddonHash[row.getString(1)] = row.toEntity()
        }

        return listOfAddonHash
    }
}