package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration11to12 :
    DatabaseMigration(11, 12, "Improved notification tables") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        renamePropertiesColumnToDetailsInNotificationsTable(),
        renameDateColumnToCreatedAtInNotificationsTable(),
        addUpdatedAtColumnToNotificationsTable(),
        updateUpdatedAtColumnToNotificationsTable(),

        renamePropertiesColumnToDetailsInPanelNotificationsTable(),
        renameDateColumnToCreatedAtInPanelNotificationsTable(),
        addUpdatedAtColumnToPanelNotificationsTable(),
        updateUpdateddAtColumnToPanelNotificationsTable()
    )

    private fun renamePropertiesColumnToDetailsInNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}notification` RENAME COLUMN `properties` TO `details`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun renameDateColumnToCreatedAtInNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}notification` RENAME COLUMN `date` TO `createdAt`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun addUpdatedAtColumnToNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}notification` ADD COLUMN updatedAt BIGINT(20) NOT NULL;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun updateUpdatedAtColumnToNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "UPDATE `${getTablePrefix()}notification` SET updatedAt = createdAt;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun renamePropertiesColumnToDetailsInPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}panel_notification` RENAME COLUMN `properties` TO `details`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun renameDateColumnToCreatedAtInPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}panel_notification` RENAME COLUMN `date` TO `createdAt`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun addUpdatedAtColumnToPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}panel_notification` ADD COLUMN updatedAt BIGINT(20) NOT NULL;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun updateUpdateddAtColumnToPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "UPDATE `${getTablePrefix()}panel_notification` SET updatedAt = createdAt;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }
}