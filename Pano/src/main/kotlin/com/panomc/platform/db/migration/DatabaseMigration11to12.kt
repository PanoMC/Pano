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
            renameColumnIfPresent(sqlClient, "${getTablePrefix()}notification", "properties", "details")
        }

    private fun renameDateColumnToCreatedAtInNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            renameColumnIfPresent(sqlClient, "${getTablePrefix()}notification", "date", "createdAt")
        }

    private fun addUpdatedAtColumnToNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}notification", "updatedAt" to "BIGINT(20) NOT NULL")
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
            renameColumnIfPresent(sqlClient, "${getTablePrefix()}panel_notification", "properties", "details")
        }

    private fun renameDateColumnToCreatedAtInPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            renameColumnIfPresent(sqlClient, "${getTablePrefix()}panel_notification", "date", "createdAt")
        }

    private fun addUpdatedAtColumnToPanelNotificationsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}panel_notification", "updatedAt" to "BIGINT(20) NOT NULL")
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