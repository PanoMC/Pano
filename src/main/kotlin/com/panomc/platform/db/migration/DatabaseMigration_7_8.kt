package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_7_8(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 7
    override val SCHEME_VERSION = 8
    override val SCHEME_VERSION_INFO = "Restore post, postCategory, ticket and ticketCategory tables."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            createPostTable(),
            createPostCategoryTable(),
            createTicketTable(),
            createTicketCategoryTable()
        )

    private fun createPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}post` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` varchar(255) NOT NULL,
                              `category_id` varchar(11) NOT NULL,
                              `writer_user_id` int(11) NOT NULL,
                              `post` longblob NOT NULL,
                              `date` int(50) NOT NULL,
                              `move_date` int(50) NOT NULL,
                              `status` int(1) NOT NULL,
                              `image` longblob NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Posts table.';
                        """
                )
                .execute()
                .await()
        }

    private fun createPostCategoryTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}post_category` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` varchar(255) NOT NULL,
                              `description` text NOT NULL,
                              `url` varchar(255) NOT NULL,
                              `color` varchar(6) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post category table.';
                        """
                )
                .execute()
                .await()

            sqlConnection
                .preparedQuery("INSERT INTO ${getTablePrefix()}post_category (title, description, url, color) VALUES (?, ?, ?, ?)")
                .execute(Tuple.of("Genel", "Genel", "genel", "48CFAD"))
                .await()

            sqlConnection
                .preparedQuery("INSERT INTO ${getTablePrefix()}post_category (title, description, url, color) VALUES (?, ?, ?, ?)")
                .execute(Tuple.of("Duyuru", "Duyuru", "duyuru", "5D9CEC"))
                .await()

            sqlConnection
                .preparedQuery("INSERT INTO ${getTablePrefix()}post_category (title, description, url, color) VALUES (?, ?, ?, ?)")
                .execute(Tuple.of("Haber", "Haber", "haber", "FFCE54"))
                .await()
        }

    private fun createTicketTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}ticket` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` varchar(255) NOT NULL,
                              `ticket_category_id` varchar(11) NOT NULL,
                              `user_id` int(11) NOT NULL,
                              `date` int(50) NOT NULL,
                              `status` int(1) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tickets table.';
                        """
                )
                .execute()
                .await()
        }

    private fun createTicketCategoryTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}ticket_category` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` varchar(255) NOT NULL,
                              `description` text,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ticket category table.';
                        """
                )
                .execute()
                .await()

            sqlConnection
                .preparedQuery("INSERT INTO ${getTablePrefix()}ticket_category (title) VALUES (?)")
                .execute(Tuple.of("Genel"))
                .await()
        }
}