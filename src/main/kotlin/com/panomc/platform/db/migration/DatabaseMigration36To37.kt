package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration36To37(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 36
    override val SCHEME_VERSION = 37
    override val SCHEME_VERSION_INFO =
        "Update status column & delete secret_key & public_key & token columns from server table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            deleteSecretKeyColumn(),
            deletePublicKeyColumn(),
            deleteTokenColumn(),
            updateServerTableStatusColumn()
        )

    private fun deleteSecretKeyColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` DROP COLUMN `secret_key`;")
                .execute()
                .await()
        }

    private fun deletePublicKeyColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` DROP COLUMN `public_key`;")
                .execute()
                .await()
        }

    private fun deleteTokenColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` DROP COLUMN `token`;")
                .execute()
                .await()
        }

    private fun updateServerTableStatusColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` MODIFY `status` int(1);")
                .execute()
                .await()
        }
}