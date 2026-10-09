package com.panomc.platform.route.api


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.error.InvalidIpAddress
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import java.net.InetAddress
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class VisitorVisitAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/visitor-visit", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Counts one visit of the caller's IP address for today.",
        tag = "site",
        response = objectSchema(),
        errors = listOf(InvalidIpAddress::class)
    )

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
        if (!ipAddress.matches(IPV4_REGEX) && !isIpv6Literal(ipAddress)) {
            throw InvalidIpAddress()
        }
    }

    /**
     * IPv6 visitors used to be rejected outright and never counted. Only hex digits, colons and an
     * embedded IPv4 tail get this far, so [InetAddress.getByName] parses a literal and never resolves.
     */
    private fun isIpv6Literal(ipAddress: String): Boolean {
        if (!ipAddress.contains(':') || !ipAddress.matches(IPV6_CHARS_REGEX)) {
            return false
        }

        return try {
            // Not `is Inet6Address`: an IPv4-mapped literal (::ffff:1.2.3.4) parses to an Inet4Address.
            InetAddress.getByName(ipAddress)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val IPV4_REGEX =
            Regex("^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!\$)|\$)){4}\$")
        private val IPV6_CHARS_REGEX = Regex("^[0-9a-fA-F:.]{2,45}\$")
    }
}