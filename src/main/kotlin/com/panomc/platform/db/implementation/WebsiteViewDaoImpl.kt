package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.WebsiteViewDao
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.DateUtil
import com.panomc.platform.util.TimeUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class WebsiteViewDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "website_view"), WebsiteViewDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `times` bigint NOT NULL,
                          `date` BIGINT(20) NOT NULL,
                          `ip_address` varchar(255) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Website visitor view table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun isIpAddressExistsByToday(
        ipAddress: String,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `ip_address` = ? && `date` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ipAddress, DateUtil.getTodayInMillis()))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun add(websiteView: WebsiteView, sqlConnection: SqlConnection): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`times`, `date`, `ip_address`) " +
                    "VALUES (?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    websiteView.times,
                    websiteView.date,
                    websiteView.ipAddress
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun increaseTimesByOne(ipAddress: String, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `times` = `times` + 1 WHERE `ip_address` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ipAddress
                )
            )
            .await()
    }

    override suspend fun getVisitorDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): List<Long> {
        val query = "SELECT `date` FROM `${getTablePrefix() + tableName}` WHERE `date` > ? GROUP BY `ip_address`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun getViewDatesAndTimesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): Map<Long, Long> {
        val query =
            "SELECT `date`, `times` FROM `${getTablePrefix() + tableName}` WHERE `date` > ? GROUP BY `ip_address`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList().associate { it.getLong(0) to it.getLong(1) }
    }
}