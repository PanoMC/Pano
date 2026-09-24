package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.LocalNodeJarLocator
import com.panomc.platform.node.NodeJarBundle
import com.panomc.platform.node.NodeJarProvider
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The node daemon itself (`GET /api/node/pano-node.jar`), and the same bytes as the Pano Agent
 * (`GET /api/node/pano-agent.jar`, SM-74): the agent is this jar saved as `pano-agent.jar` in a
 * server's folder, and it is served under that name so a browser download or `curl -O` lands it
 * with the name that makes it an agent.
 *
 * The bytes come straight out of the `pano-node.zip` bundled in the Pano jar, never from a file on
 * disk, so that installing a node never depends on a GitHub release existing or on what happens to
 * sit next to Pano — a development build, an air-gapped network or a host that can reach Pano and
 * nothing else all work, and the daemon a node ends up running is by construction the one this Pano
 * speaks its protocol with.
 *
 * Streamed in chunks off the event loop, each one written before the next is read, so a burst of
 * nodes updating at once costs a buffer each rather than a copy of the jar each.
 *
 * Public and unauthenticated, at the same trust level as `GET /api/node/install.sh`: the jar is a
 * published artifact, not a secret, and it grants nothing on its own — a node still has to pair
 * with a code or a bootstrap token before Pano will talk to it. Whoever downloads it can verify
 * what they got against [NodeJarChecksumAPI].
 *
 * 404 only for a Pano jar somebody assembled without the daemon; the installer then falls back to
 * the release URL that [com.panomc.platform.node.NodeInstallScriptProvider] renders in that case.
 */
@Endpoint
class NodeJarAPI(
    private val nodeJarProvider: NodeJarProvider
) : Api() {
    override val paths = listOf(
        Path("/api/node/${LocalNodeJarLocator.JAR_NAME}", RouteType.GET),
        Path("/api/node/${LocalNodeJarLocator.AGENT_JAR_NAME}", RouteType.GET)
    )

    // Setting a node up is exactly the kind of work an operator does *during* maintenance.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val daemon = nodeJarProvider.describe() ?: return NotExists()

        val response = context.response()
            .putHeader("Content-Type", "application/java-archive")
            .putHeader("Content-Length", daemon.size.toString())
            .putHeader("Content-Disposition", "attachment; filename=\"${servedName(context.normalizedPath())}\"")
            // Nothing here is ever a document; a browser must not be talked into treating it as
            // one by whatever it thinks the bytes look like.
            .putHeader("X-Content-Type-Options", "nosniff")
            // The bytes change whenever Pano is updated or rebuilt and the URL does not, so a
            // cached copy would install yesterday's daemon.
            .putHeader("Cache-Control", "no-store")

        withContext(Dispatchers.IO) {
            val jar = NodeJarBundle.openJar() ?: throw IllegalStateException("${LocalNodeJarLocator.JAR_NAME} vanished from the bundle")

            jar.use { stream ->
                val buffer = ByteArray(NodeJarProvider.BUFFER_SIZE)

                while (!response.closed()) {
                    val read = stream.read(buffer)

                    if (read < 0) {
                        break
                    }

                    // Awaiting each write is the back-pressure: the next chunk is read only once
                    // this one has left for the wire, so a slow client never queues up the jar.
                    response.write(Buffer.buffer(read).appendBytes(buffer, 0, read)).coAwait()
                }
            }
        }

        if (!response.closed()) {
            response.end().coAwait()
        }

        return null
    }

    companion object {
        /** The name the jar is served under at [path]: the agent's name on the agent's route. */
        fun servedName(path: String): String =
            if (path.trimEnd('/').endsWith("/${LocalNodeJarLocator.AGENT_JAR_NAME}")) {
                LocalNodeJarLocator.AGENT_JAR_NAME
            } else {
                LocalNodeJarLocator.JAR_NAME
            }
    }
}
