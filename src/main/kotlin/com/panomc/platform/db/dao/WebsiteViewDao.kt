package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.util.DashboardPeriodType
import io.vertx.sqlclient.SqlClient

interface WebsiteViewDao : Dao<WebsiteView> {
    suspend fun isIpAddressExistsByToday(
        ipAddress: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun add(
        websiteView: WebsiteView,
        sqlClient: SqlClient
    ): Long

    suspend fun increaseTimesByOne(
        ipAddress: String,
        sqlClient: SqlClient
    )

    suspend fun getWebsiteViewListByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<WebsiteView>
}