package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeDaemonUpdateService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Updates the daemon on one node (`POST /api/panel/nodes/:id/update`).
 *
 * Until now a remote node could be installed and never upgraded: `SELF_UPDATE` existed on both
 * sides and nothing in Pano ever sent it, so the only way to move a node onto a newer protocol was
 * to SSH in and replace the jar by hand. This is the missing half — Pano already serves its own
 * daemon at `GET /api/node/pano-node.jar`, so the update is the node being told to fetch the jar
 * that is by construction the one this Pano speaks to.
 *
 * The URL is relative. Pano does not know which address this node reaches it on — a tunnel, a LAN
 * address, the public hostname — and the node resolves it against the address it is already
 * connected over, which is the one address certain to work.
 *
 * [SelfUpdateMessage.sha256] is what makes this safe to send over a plain-HTTP lab network: the
 * node is being told to download and then execute code, and it verifies the bytes before swapping
 * anything. After the swap it exits 75, which the installer's systemd unit treats as a restart.
 *
 * The sending itself lives in [NodeDaemonUpdateService], which the automatic update after a hello
 * (`managed-servers.node-auto-update`) and a Pano Agent's server-scoped update use as well, so a
 * node is only ever told to replace itself one way.
 */
@Endpoint
class PanelUpdateNodeDaemonAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeDaemonUpdateService: NodeDaemonUpdateService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id/update", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        return respond(nodeDaemonUpdateService.update(id))
    }

    companion object {
        /**
         * The response for one [NodeDaemonUpdateService.Outcome], shared with the agent update of a
         * server so both answer the same way.
         *
         * No jar is a 404: this Pano was started from something other than a release install and
         * has no daemon on disk, and the panel hides the button then; this is the guard for the
         * request that arrives anyway. Already up to date is `{ upToDate: true }` -- development
         * builds make that the common case, since both sides call themselves `local-build` and only
         * the checksum can tell. Otherwise the version that was *offered*, not one that has been
         * applied: the node downloads, verifies, stages and restarts, and reports each step as a
         * SELF_UPDATE task, which Pano follows as the node's `updateProgress` and its servers'
         * `daemonUpdate` until the hello after the restart (SM-77, see
         * [com.panomc.platform.node.NodeUpdateProgressStore]).
         */
        fun respond(outcome: NodeDaemonUpdateService.Outcome): Result = when (outcome) {
            NodeDaemonUpdateService.Outcome.NoJar -> throw NotExists()
            NodeDaemonUpdateService.Outcome.Offline -> throw NodeOffline()
            is NodeDaemonUpdateService.Outcome.UpToDate -> Successful(mapOf("upToDate" to true))
            is NodeDaemonUpdateService.Outcome.Sent ->
                Successful(mapOf("version" to outcome.version, "sha256" to outcome.sha256))
        }
    }
}
