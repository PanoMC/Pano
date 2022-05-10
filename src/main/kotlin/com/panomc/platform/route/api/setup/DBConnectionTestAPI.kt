package com.panomc.platform.route.api.setup

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.PoolOptions
import org.slf4j.Logger

@Endpoint
class DBConnectionTestAPI(private val logger: Logger, private val setupManager: SetupManager) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/dbConnectionTest")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
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

        mySQLPool.getConnection { connection ->
            if (connection.succeeded())
                connection.result().close {
                    mySQLPool.close {
                        handler.invoke(Successful())
                    }
                }
            else
                mySQLPool.close {
                    logger.error(connection.cause().toString())

                    handler.invoke(Error(ErrorCode.INVALID_DATA))
                }
        }
    }
}