package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.WebsiteViewDao
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.DateUtil
import com.panomc.platform.util.TimeUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class WebsiteViewDaoImpl : WebsiteViewDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `times` bigint NOT NULL,
                          `date` BIGINT(20) NOT NULL,
                          `ipAddress` varchar(255) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Website visitor view table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun isIpAddressExistsByToday(
        ipAddress: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `ipAddress` = ? && `date` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ipAddress, DateUtil.getTodayInMillis()))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun add(websiteView: WebsiteView, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`times`, `date`, `ipAddress`) " +
                    "VALUES (?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
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

    override suspend fun increaseTimesByOne(ipAddress: String, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `times` = `times` + 1 WHERE `ipAddress` = ? AND `date` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ipAddress, DateUtil.getTodayInMillis()
                )
            )
            .await()
    }

    override suspend fun getWebsiteViewListByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<WebsiteView> {
        val query =
            "SELECT `id`, `times`, `date`, `ipAddress` FROM `${getTablePrefix() + tableName}` WHERE `date` > ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toEntities()
    }
}