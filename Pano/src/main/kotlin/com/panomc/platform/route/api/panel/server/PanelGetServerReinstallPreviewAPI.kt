package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerSoftwareChangeService
import com.panomc.platform.route.api.panel.software.PanelGetSoftwareVersionAPI
import com.panomc.platform.server.software.ServerSoftwareFamily
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import com.panomc.platform.server.InPlaceServerRules
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * What a reinstall or software change of a managed server would do, for the danger-zone modal
 * (SM-66, §2.4.31).
 *
 * `GET /api/panel/servers/:id/reinstall-preview?software=&version=` (both optional: the current
 * software, and the current version or the target's recommended release) →
 * `{ from: { software, version, family, kind }, to: { … }, defaults: { worlds, plugins, configs },
 * allowed: { … }, reasons: { worlds, plugins, configs }, java: { minimum, maximum }, running,
 * backupEstimateBytes }`. `kind` is `backend` or `proxy`; `allowed` is exactly what the reinstall
 * accepts in `keep`, so a box the modal enables is never refused, and `reasons` says why a refused
 * one is refused (`PROXY`, `FAMILY_CHANGED`, `NO_PLUGINS`, `NODE_TOO_OLD`; null when allowed). `backupEstimateBytes` is the server directory's last measured size, null when
 * nothing has measured it yet. Answers while the node is offline — only the reinstall itself needs
 * it online.
 */
@Endpoint
class PanelGetServerReinstallPreviewAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val softwareChangeService: ManagedServerSoftwareChangeService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/reinstall-preview", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("software", stringSchema()))
            .queryParameter(optionalParam("version", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val software = parameters.queryParameter("software")?.string
        val version = parameters.queryParameter("version")?.string

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.isManaged) {
            throw ServerCapabilityMissing()
        }

        // Same refusal as the reinstall itself, so a panel that asks first learns it here.
        InPlaceServerRules.requireReinstallable(server)

        val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: throw NodeOffline()

        val target = softwareChangeService.resolveTarget(server, node, software, version)

        return Successful(
            mapOf(
                // The catalog id; an imported row without one falls back to its type, whose names
                // are the catalog's ids in upper case.
                "from" to side(
                    server.software?.lowercase() ?: server.type.name.lowercase(),
                    server.softwareVersion ?: server.version,
                    target.from
                ),
                "to" to side(target.software, target.version, target.to),
                "defaults" to target.defaults.toJson().map,
                "allowed" to target.allowed.toJson().map,
                "reasons" to target.reasons,
                "java" to PanelGetSoftwareVersionAPI.javaRange(target.version, target.resolution?.javaMajor).map,
                "running" to (server.processState?.isAlive == true),
                "backupEstimateBytes" to server.diskUsed
            )
        )
    }

    private fun side(software: String?, version: String?, family: ServerSoftwareFamily) = mapOf(
        "software" to software,
        "version" to version,
        "family" to family.id,
        "kind" to family.kind
    )
}
