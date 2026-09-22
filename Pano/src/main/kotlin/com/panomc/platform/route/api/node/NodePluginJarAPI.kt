package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.ManagedPluginJarResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * The Pano plugin build a node should put inside a managed server
 * (`GET /api/node/plugin-jars/:platform`).
 *
 * Exists because `managed-servers.plugin-jar-dir` used to be handed to nodes as a `file://` URL.
 * That works for the daemon Pano runs beside itself and for nothing else: a node on another machine
 * cannot open a path in somebody's home directory, so the install threw, the server came up without
 * the plugin, and the task still said DONE. Publishing the jar turns a path only one host can read
 * into an artifact every node can fetch from the Pano it is already talking to.
 *
 * Public and unauthenticated, at the same trust level as [NodeJarAPI] and `GET /api/node/install.sh`:
 * the plugin is a published artifact of `PanoMC/pano-mc-plugin` that anybody can download from
 * GitHub, and it carries no credential — the token and the AES key travel inside `INSTALL_SERVER`,
 * on an encrypted socket, and are written next to the jar by the node.
 *
 * 404 when this install configures no local jar directory or has no build for that platform; the
 * resolver then points nodes at the GitHub release instead and never emits this URL at all.
 */
@Endpoint
class NodePluginJarAPI(
    private val managedPluginJarResolver: ManagedPluginJarResolver
) : Api() {
    override val paths = listOf(Path("${ManagedPluginJarResolver.PLUGIN_JAR_PATH}/:platform", RouteType.GET))

    // Installing a server is exactly the kind of work an operator does during maintenance.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val platform = context.pathParam("platform")?.lowercase()

        // Checked against the known modules rather than sanitised: the value becomes part of a
        // file search, and a closed set is the only validation that cannot be argued with.
        if (platform !in ManagedPluginJarResolver.PLATFORMS) {
            return NotExists()
        }

        val jar = managedPluginJarResolver.localJarFor(platform!!) ?: return NotExists()

        context.response()
            .putHeader("Content-Type", "application/java-archive")
            .putHeader("Content-Length", jar.length().toString())
            // The real name, so a development build keeps `pano-spigot-local-build.jar` and a
            // release asset keeps its version: the node names the file it writes after this.
            .putHeader("Content-Disposition", "attachment; filename=\"${jar.name}\"")
            .putHeader("X-Content-Type-Options", "nosniff")
            // Rebuilt constantly during development and the URL never changes, so a cached copy
            // would install the jar from before the last `./gradlew build`.
            .putHeader("Cache-Control", "no-store")
            .sendFile(jar.absolutePath)
            .coAwait()

        return null
    }
}
