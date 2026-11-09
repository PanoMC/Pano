package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InvalidData
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.PoolOptions
import org.slf4j.Logger

@Endpoint
class Step2VerifyDBAPI(private val logger: Logger) : SetupApi() {
    override val paths = listOf(Path("/setup/steps/2/verify", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("host", Schemas.stringSchema())
                        .requiredProperty("dbName", Schemas.stringSchema())
                        .requiredProperty("username", Schemas.stringSchema())
                        .optionalProperty("password", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        // Pano Host gives the database; the wizard never tests other servers from inside the Portal.
        if (setupManager.isDatabaseManaged()) {
            throw BadRequest()
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val (host, port) = parseDatabaseHost(data.getString("host")) ?: throw BadRequest()

        val connectOptions = MySQLConnectOptions()
            .setPort(port)
            .setHost(host)
            .setDatabase(data.getString("dbName"))
            .setUser(data.getString("username"))

        // An empty password keeps the env-seeded one the setup API never returned.
        val password = setupManager.databasePasswordFor(
            data.getString("host"), data.getString("dbName"), data.getString("username"), data.getString("password")
        )

        if (password.isNotEmpty())
            connectOptions.password = password

        val poolOptions = PoolOptions()
            .setMaxSize(1)

        val mySQLPool = MySQLBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(context.vertx())
            .build()

        try {
            val connection = mySQLPool.connection.coAwait()

            connection.close().coAwait()

            mySQLPool.close().coAwait()
        } catch (e: java.lang.Exception) {
            logger.error(e.toString())

            throw InvalidData()
        }

        return Successful()
    }
}

/**
 * `host`, `host:port`, `[ipv6]` or `[ipv6]:port` → host and port (3306 when absent); null for
 * anything else, such as a non-numeric or out-of-range port or a bare IPv6 address with a port.
 */
internal fun parseDatabaseHost(raw: String?, defaultPort: Int = 3306): Pair<String, Int>? {
    val value = raw?.trim().orEmpty()

    if (value.isEmpty()) return null

    val host: String
    val portText: String?

    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        if (end < 2) return null
        host = value.substring(1, end)
        val rest = value.substring(end + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
    } else {
        val colons = value.count { it == ':' }
        when {
            colons == 0 -> { host = value; portText = null }
            colons == 1 -> { host = value.substringBefore(':'); portText = value.substringAfter(':') }
            // A bare IPv6 address: no port can be told apart from it.
            else -> { host = value; portText = null }
        }
    }

    if (host.isEmpty()) return null

    val port = if (portText == null) defaultPort else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

    return host to port
}
