package com.panomc.platform.route.api.setup

import com.panomc.platform.ErrorCode
import com.panomc.platform.Main
import com.panomc.platform.model.*
import io.vertx.core.logging.Logger
import io.vertx.ext.web.RoutingContext
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.PoolOptions
import javax.inject.Inject

class DBConnectionTestAPI : SetupApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/dbConnectionTest")

    @Inject
    lateinit var logger: Logger

    init {
        Main.getComponent().inject(this)
    }


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
                    logger.error(connection.cause())

                    handler.invoke(Error(ErrorCode.INVALID_DATA))
                }
        }
    }
}