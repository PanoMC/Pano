package com.panomc.platform.route.api.setup

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.PoolOptions
import org.slf4j.Logger

@Endpoint
class DBConnectionTestAPI(private val logger: Logger, private val setupManager: SetupManager) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/dbConnectionTest")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        var port = 3306
        var host = data.getString("host")

        if (host.contains(":")) {
            val splitHost = host.split(":")

            host = splitHost[0]

            port = splitHost[1].toInt()
        }

        val connectOptions = MySQLConnectOptions()
            .setPort(port)
            .setHost(host)
            .setDatabase(data.getString("dbName"))
            .setUser(data.getString("username"))

        if (!data.getString("password").isNullOrEmpty())
            connectOptions.password = data.getString("password")

        val poolOptions = PoolOptions()
            .setMaxSize(1)

        val mySQLPool = MySQLPool.pool(context.vertx(), connectOptions, poolOptions)

        try {
            val connection = mySQLPool.connection.await()

            connection.close().await()

            mySQLPool.close().await()

            return Successful()
        } catch (e: java.lang.Exception) {
            logger.error(e.toString())

            throw Error(ErrorCode.INVALID_DATA)
        }
    }
}