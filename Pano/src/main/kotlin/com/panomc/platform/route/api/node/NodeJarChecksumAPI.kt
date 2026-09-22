package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.LocalNodeJarLocator
import com.panomc.platform.node.NodeJarProvider
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * The SHA-256 of the jar [NodeJarAPI] serves (`GET /api/node/pano-node.jar.sha256`, and
 * `/api/node/pano-agent.jar.sha256` naming the same bytes as `pano-agent.jar`).
 *
 * The installers verify what they downloaded against this, which is the only reason the download
 * endpoint is allowed to be unauthenticated over plain HTTP on a lab network: the bytes are
 * checked, not trusted. The body is in `sha256sum` format so both the shell script and a person
 * can use it.
 *
 * Served next to the jar rather than computed by the caller for the obvious reason that a
 * checksum from the same place as the file only protects against a corrupted transfer — against
 * a hostile one it is TLS that matters, exactly as it does for the GitHub release this replaces.
 */
@Endpoint
class NodeJarChecksumAPI(
    private val nodeJarProvider: NodeJarProvider
) : Api() {
    override val paths = listOf(
        Path("/api/node/${LocalNodeJarLocator.JAR_NAME}.sha256", RouteType.GET),
        Path("/api/node/${LocalNodeJarLocator.AGENT_JAR_NAME}.sha256", RouteType.GET)
    )

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val jar = nodeJarProvider.locate() ?: return NotExists()

        context.response()
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("Cache-Control", "no-store")
            .end(nodeJarProvider.checksumBody(jar, NodeJarAPI.servedName(context.normalizedPath().removeSuffix(".sha256"))))
            .coAwait()

        return null
    }
}
