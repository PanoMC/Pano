package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ReinstalledServerLog
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.KeepIncompatible
import com.panomc.platform.error.NetworkRoleConflict
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerSoftwareChangeService
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.SoftwareChangeSteps
import com.panomc.platform.server.InPlaceServerRules
import com.panomc.platform.server.software.SoftwareChangeKeep
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Reinstalls a managed server, on the same software or on another one (SM-66, §2.4.31).
 *
 * Body: `currentPassword`, `acceptEula` (must be true), and optionally `software`, `version`,
 * `keep: { worlds, plugins, configs }`, `backupFirst` (default true) and `startAfter` (default:
 * whether the server is running now). Every optional field defaults to what the preview
 * (`GET …/reinstall-preview`) shows, so an old caller that sends only the password gets a reinstall
 * that stops the server, backs it up, keeps whatever still fits and brings it back.
 *
 * The request only validates and decides; [ManagedServerSoftwareChangeService] runs the steps as one
 * REINSTALL task, whose ids are the response. `keep` asking for something the change cannot carry
 * is a 400 `KEEP_INCOMPATIBLE` naming the refused switches in `keep`. A server run in place from its
 * own directory (`inPlace`) is refused with 409 `IN_PLACE_UNSUPPORTED`. Destructive, so it asks for
 * the password like the other destructive panel actions and is audited.
 */
@Endpoint
class PanelReinstallServerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val softwareChangeService: ManagedServerSoftwareChangeService,
    private val nodeManager: NodeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/reinstall", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .optionalProperty("software", stringSchema())
                        .optionalProperty("version", stringSchema())
                        .optionalProperty("acceptEula", booleanSchema())
                        .optionalProperty(
                            "keep",
                            objectSchema()
                                .optionalProperty("worlds", booleanSchema())
                                .optionalProperty("plugins", booleanSchema())
                                .optionalProperty("configs", booleanSchema())
                        )
                        .optionalProperty("backupFirst", booleanSchema())
                        .optionalProperty("startAfter", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val data = parameters.body().jsonObject

        if (data.getBoolean("acceptEula", false) != true) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, data.getString("currentPassword"), sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.isManaged) {
            throw ServerCapabilityMissing()
        }

        // A server run in place is somebody's own directory: nothing may be built beside it.
        InPlaceServerRules.requireReinstallable(server)

        val nodeId = server.nodeId ?: throw ServerCapabilityMissing()

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: throw NodeOffline()

        if (!nodeManager.isConnected(node.id)) {
            throw NodeOffline()
        }

        val target = softwareChangeService.resolveTarget(server, node, data.getString("software"), data.getString("version"))

        // A version the catalog cannot resolve would fail on the node minutes later, after the
        // server was stopped and backed up for nothing.
        if (target.resolution == null) {
            throw BadRequest()
        }

        // Pano keeps no proxy networks any more (see SoftwareChangeSteps.networkRoleConflict), so
        // no server is a member of one and this never refuses today.
        if (SoftwareChangeSteps.networkRoleConflict(inNetwork = false, from = target.from, to = target.to)) {
            throw NetworkRoleConflict()
        }

        val keep = SoftwareChangeKeep.parse(data.getJsonObject("keep"), target.defaults) ?: target.defaults

        val refused = keep.disallowedBy(target.allowed)

        if (refused.isNotEmpty()) {
            throw KeepIncompatible(
                extras = mapOf("keep" to refused, "reasons" to refused.associateWith { target.reasons[it] })
            )
        }

        val running = server.processState?.isAlive == true

        val task = softwareChangeService.begin(
            ManagedServerSoftwareChangeService.Request(
                server = server,
                node = node,
                userId = userId,
                software = target.software,
                version = target.version,
                serverType = target.serverType,
                keep = keep,
                backupFirst = SoftwareChangeSteps.backupFirst(data.getBoolean("backupFirst")),
                startAfter = SoftwareChangeSteps.startAfter(data.getBoolean("startAfter"), running),
                running = running
            ),
            sqlClient
        )

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ReinstalledServerLog(
                userId = userId,
                username = username,
                serverId = id,
                software = target.software,
                version = target.version,
                oldSoftware = server.software,
                oldVersion = server.softwareVersion,
                keep = keep
            ),
            sqlClient
        )

        return Successful(mapOf("taskId" to task.id, "taskUuid" to task.uuid))
    }
}
