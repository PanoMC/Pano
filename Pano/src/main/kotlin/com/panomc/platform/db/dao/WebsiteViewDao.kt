package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.util.DashboardPeriodType
import io.vertx.sqlclient.SqlClient

abstract class WebsiteViewDao : Dao<WebsiteView>(WebsiteView::class.java) {
    abstract suspend fun isIpAddressExistsByToday(
        ipAddress: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun add(
        websiteView: WebsiteView,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun increaseTimesByOne(
        ipAddress: String,
        sqlClient: SqlClient
    )

    abstract suspend fun getWebsiteViewListByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<WebsiteView>
}