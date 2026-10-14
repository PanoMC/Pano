package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.MailLayoutDaoImpl
import com.panomc.platform.db.implementation.MailSettingDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `mail_layout` (overrides of bundled layouts and admin-made layouts) and `mail_setting` (each mail's layout
 * choice), and moves the single layout override (`mail_template` row `core / _layout / default / *`) into
 * `mail_layout` as `core:default`.
 */
@Migration
class DatabaseMigration62to63 : DatabaseMigration(
    62,
    63,
    "Add the mail_layout and mail_setting tables"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(MailLayoutDaoImpl.createTableQuery("${getTablePrefix()}mail_layout"))
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .query(MailSettingDaoImpl.createTableQuery("${getTablePrefix()}mail_setting"))
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "INSERT IGNORE INTO `${getTablePrefix()}mail_layout` (`owner`, `layout_key`, `name`, `body`, `updated_at`) " +
                            "SELECT 'core', 'default', '', `body`, `updated_at` FROM `${getTablePrefix()}mail_template` " +
                            "WHERE $OLD_LAYOUT_ROW"
                )
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .query("DELETE FROM `${getTablePrefix()}mail_template` WHERE $OLD_LAYOUT_ROW")
                .execute()
                .coAwait()
        }
    )

    private companion object {
        const val OLD_LAYOUT_ROW =
            "`owner` = 'core' AND `mail_key` = '_layout' AND `variant` = 'default' AND `locale_code` = '*'"
    }
}
