package com.panomc.platform.route.api


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.error.InvalidIpAddress
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class VisitorVisitAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/visitorVisit", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val ipAddress = authProvider.getRemoteIP(context)

        validateIpAddress(ipAddress)

        val sqlClient = getSqlClient()

        val exists = databaseManager.websiteViewDao.isIpAddressExistsByToday(ipAddress, sqlClient)

        if (exists) {
            databaseManager.websiteViewDao.increaseTimesByOne(ipAddress, sqlClient)
        } else {
            databaseManager.websiteViewDao.add(WebsiteView(ipAddress = ipAddress), sqlClient)
        }

        return Successful()
    }

    private fun validateIpAddress(ipAddress: String) {
        if (!ipAddress.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!\$)|\$)){4}\$"))) {
            throw InvalidIpAddress()
        }
    }
}