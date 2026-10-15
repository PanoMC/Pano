package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Rewrites the file URLs stored in content from the pre-cutover `/api/...` form to `/api/v1/...` (open front-end plan,
 * doc 04 section 9, "Stored URLs"): the post body and thumbnail, both notification tables and the theme settings values.
 *
 * Only the prefixes of the routes that serve a stored file are rewritten ([RENAMES], a subset of the core rename table
 * `Pano/api/paths.old-new.json`, checked by `StoredUrlMigrationTest`). The rewrite is a plain substring replace, so an
 * absolute URL (`https://site/api/post/thumbnail/a.png`) and a `?hash=` query keep working, and running it twice changes
 * nothing. The proof that nothing was missed is the generic column scan (`scripts/api-v1/column-scan.mjs`), not this list.
 */
@Migration
class DatabaseMigration58to59 : DatabaseMigration(
    58,
    59,
    "Rewrite stored /api file URLs to /api/v1"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            statements(getTablePrefix()).forEach { statement ->
                sqlClient
                    .preparedQuery(statement)
                    .execute()
                    .coAwait()
            }
        }
    )

    companion object {
        /** Old prefix to new prefix, for every route that serves a file whose URL is stored in content. */
        val RENAMES: List<Pair<String, String>> = listOf(
            "/api/post/thumbnail/" to "/api/v1/posts/thumbnails/",
            "/api/theme/file/" to "/api/v1/theme/file/",
            "/api/profile/picture/" to "/api/v1/profile/picture/",
            "/api/server/icon/" to "/api/v1/server/icon/",
            "/api/panel/updates/icon/" to "/api/v1/panel/updates/icon/",
            "/api/websiteLogo" to "/api/v1/website-logo",
            "/api/favicon" to "/api/v1/favicon"
        )

        /** The columns that are rewritten: table name without prefix, its columns and an optional extra row filter. */
        internal val TARGETS: List<Triple<String, List<String>, String?>> = listOf(
            Triple("post", listOf("text", "thumbnailUrl"), null),
            Triple("notification", listOf("details"), null),
            Triple("panel_notification", listOf("details"), null),
            Triple("system_property", listOf("value"), "`option` = 'theme_settings'")
        )

        /** What the SQL does to one value, in Kotlin: the reference the SQL is compared with. */
        fun rewrite(text: String): String = RENAMES.fold(text) { current, (old, new) -> current.replace(old, new) }

        /** `REPLACE(REPLACE(`column`, old, new), ...)` for [column]. */
        internal fun replaceExpression(column: String): String =
            RENAMES.fold("`$column`") { expression, (old, new) -> "REPLACE($expression, '$old', '$new')" }

        /** One `UPDATE` per table, touching only the rows that hold `/api/`. [tablePrefix] is the install's table prefix. */
        fun statements(tablePrefix: String): List<String> = TARGETS.map { (table, columns, filter) ->
            val set = columns.joinToString(", ") { "`$it` = ${replaceExpression(it)}" }
            val holds = columns.joinToString(" OR ") { "`$it` LIKE '%/api/%'" }
            val where = if (filter == null) holds else "$filter AND ($holds)"

            "UPDATE `$tablePrefix$table` SET $set WHERE $where"
        }
    }
}
