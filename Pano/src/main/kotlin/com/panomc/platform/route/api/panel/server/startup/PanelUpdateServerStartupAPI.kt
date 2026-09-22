package com.panomc.platform.route.api.panel.server.startup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedServerStartupLog
import com.panomc.platform.auth.panel.permission.ManageServerStartupPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.ServerStartupLimits
import com.panomc.platform.node.message.StartupSpec
import com.panomc.platform.node.message.UpdateStartupMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerPropertyKeys
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Changes how a managed server is launched: Java version, heap, flags, port and the two
 * automation switches.
 *
 * `properties` are the `server.properties` entries Pano manages (see [ServerPropertyKeys]); the
 * node merges them into the real file, so keys nobody here has heard of survive untouched.
 *
 * Stored first and pushed second, and deliberately not applied to a running process — the node
 * uses them on the next launch. A push that fails is not an error: the settings are on the row,
 * and the node reads them again from `UPDATE_STARTUP` when it next reconnects.
 */
@Endpoint
class PanelUpdateServerStartupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/startup", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        // Nullable throughout: null is how the panel says "automatic" for the
                        // Java version and "let the node allocate" for the port.
                        .optionalProperty("javaMajor", intSchema().nullable())
                        .optionalProperty("memoryMb", intSchema())
                        .optionalProperty("jvmArgs", arraySchema().items(stringSchema()))
                        .optionalProperty("port", intSchema().nullable())
                        .optionalProperty("autoStart", booleanSchema())
                        .optionalProperty("crashRestart", booleanSchema())
                        // Values arrive as strings, numbers or booleans depending on the form
                        // control behind them; ServerPropertyKeys is what decides which keys are
                        // Pano's to write at all.
                        .optionalProperty("properties", objectSchema().nullable())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerStartupPermission(), context, id)

        val data = parameters.body().jsonObject

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.isManaged) {
            throw ServerCapabilityMissing()
        }

        val javaMajor = if (data.containsKey("javaMajor")) data.getInteger("javaMajor") else server.javaVersion
        val memoryMb = if (data.containsKey("memoryMb")) data.getInteger("memoryMb") else server.memoryMb
        val port = if (data.containsKey("port")) data.getInteger("port") else server.gamePort
        val jvmArgs = if (data.containsKey("jvmArgs")) readJvmArgs(data.getJsonArray("jvmArgs")) else server.jvmArgs
        val autoStart = data.getBoolean("autoStart", server.autoStart)
        val crashRestart = data.getBoolean("crashRestart", server.crashRestart)

        // Merged onto what is stored, not replacing it: the panel sends the fields its form knows
        // about, and a key it never showed must not disappear from the server because of that.
        val properties = if (data.containsKey("properties")) {
            server.properties + ServerPropertyKeys.read(data.getJsonObject("properties"))
        } else {
            server.properties
        }

        if ((javaMajor != null && javaMajor !in MIN_JAVA_MAJOR..MAX_JAVA_MAJOR) ||
            (memoryMb != null && memoryMb !in MIN_MEMORY_MB..MAX_MEMORY_MB) ||
            (port != null && port !in MIN_PORT..MAX_PORT)
        ) {
            throw BadRequest()
        }

        databaseManager.serverDao.updateStartupById(
            id = id,
            javaVersion = javaMajor,
            memoryMb = memoryMb,
            jvmArgs = jvmArgs,
            properties = properties,
            gamePort = port,
            autoStart = autoStart,
            crashRestart = crashRestart,
            sqlClient = sqlClient
        )

        val uuid = server.uuid
        val nodeId = server.nodeId

        if (uuid != null && nodeId != null) {
            nodeManager.sendMessage(
                nodeId,
                UpdateStartupMessage(
                    serverUuid = uuid,
                    spec = StartupSpec(
                        javaMajor = javaMajor,
                        memoryMb = memoryMb,
                        jvmArgs = jvmArgs,
                        port = port,
                        autoStart = autoStart,
                        crashRestart = crashRestart,
                        properties = properties
                    )
                )
            )
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(UpdatedServerStartupLog(userId, username, id), sqlClient)

        panelRealtimeHub.notifyServerUpdated(id)

        // Echoed back so the panel prefills the form from what was actually stored rather than
        // from what it sent, which are different whenever a key was dropped as unsupported.
        return Successful(
            mapOf(
                "javaMajor" to javaMajor,
                "memoryMb" to memoryMb,
                "jvmArgs" to jvmArgs,
                "port" to port,
                "autoStart" to autoStart,
                "crashRestart" to crashRestart,
                "properties" to ServerPropertyKeys.toJsonObject(properties)
            )
        )
    }

    private fun readJvmArgs(array: JsonArray?): List<String> = ServerStartupLimits.jvmArgs((array ?: JsonArray()).list)

    companion object {
        private const val MIN_JAVA_MAJOR = 8
        private const val MAX_JAVA_MAJOR = 64
        private const val MIN_MEMORY_MB = ServerStartupLimits.MIN_MEMORY_MB
        private const val MAX_MEMORY_MB = ServerStartupLimits.MAX_MEMORY_MB
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }
}
