package com.panomc.platform.route.api

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.WebsiteView
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class VisitorVisitAPI(private val databaseManager: DatabaseManager) : Api() {

    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/visitorVisit")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("ipAddress", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val ipAddress = data.getString("ipAddress")

        validateIpAddress(ipAddress)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.websiteViewDao.isIpAddressExistsByToday(ipAddress, sqlConnection)

        if (exists) {
            databaseManager.websiteViewDao.increaseTimesByOne(ipAddress, sqlConnection)
        } else {
            databaseManager.websiteViewDao.add(WebsiteView(ipAddress = ipAddress), sqlConnection)
        }

        return Successful()
    }

    private fun validateIpAddress(ipAddress: String) {
        if (!ipAddress.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!\$)|\$)){4}\$"))) {
            throw Error(ErrorCode.INVALID_IP_ADDRESS)
        }
    }
}