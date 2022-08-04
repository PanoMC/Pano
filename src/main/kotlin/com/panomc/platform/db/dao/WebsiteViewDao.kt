package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.util.DashboardPeriodType
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface WebsiteViewDao : Dao<WebsiteView> {
    suspend fun isIpAddressExistsByToday(
        ipAddress: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun add(
        websiteView: WebsiteView,
        sqlConnection: SqlConnection
    ): Long

    suspend fun increaseTimesByOne(
        ipAddress: String,
        sqlConnection: SqlConnection
    )

    suspend fun getWebsiteViewListByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): List<WebsiteView>
}