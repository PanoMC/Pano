package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.backup.PanoBackupSettings
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * Adds the local Pano Backup settings (schedule, hour, how many scheduled backups to keep) as a
 * `system_property` row with the defaults, schedule off. Fresh installs get the same row from
 * `SystemPropertyDaoImpl.init`.
 */
@Migration
class DatabaseMigration55to56 : DatabaseMigration(
    55,
    56,
    "Add the Pano Backup settings"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            val table = "`${getTablePrefix()}system_property`"
            val now = System.currentTimeMillis()

            // Guarded so a migration that was interrupted is safe to run again.
            sqlClient
                .preparedQuery(
                    "INSERT INTO $table (`option`, `value`, `createdAt`, `updatedAt`) " +
                            "SELECT ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM $table WHERE `option` = ?)"
                )
                .execute(
                    Tuple.of(
                        PanoBackupSettings.OPTION,
                        PanoBackupSettings().toJson().encode(),
                        now,
                        now,
                        PanoBackupSettings.OPTION
                    )
                )
                .coAwait()
        }
    )
}
