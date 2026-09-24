package com.panomc.platform.node

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.message.ManagedPluginConfig
import com.panomc.platform.node.message.ManagedPluginSpec
import com.panomc.platform.token.ServerAuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Hands a managed server the credentials its Pano plugin would otherwise be given by hand.
 *
 * A linked server is paired by a human typing `/pano connect <address> <code>`: the plugin posts
 * its public key, Pano writes a row, issues a token and wraps an AES key for it. Nothing about
 * that dance is needed when Pano is the one creating the server — it already owns the row, so it
 * can simply write the answer into the plugin's config before the server has ever started.
 *
 * The token's subject is the server row's id, which is what makes the plugin attach to *that* row
 * on connect instead of creating a second one. Everything else about the handshake stays as it
 * is: `ON_SERVER_CONNECT` updates the row it authenticated as, and never touches `kind` or
 * `permissionGranted`.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedPluginLinkService(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val managedPluginDependencyResolver: ManagedPluginDependencyResolver,
    private val configManager: ConfigManager,
    private val logger: Logger
) {
    /**
     * Builds the plugin block for one install, or null when this server gets no plugin.
     *
     * Null means either that the software has no Pano plugin module (vanilla, Forge, NeoForge) or
     * that no build of it could be resolved right now; [describe] turns the same inputs into the
     * sentence the install task carries, so the panel says which of the two happened.
     *
     * Issuing the token is a side effect on purpose, and it happens per install: every previous
     * token for this server is invalidated first, so a reinstall cannot leave a credential lying
     * in a directory that is about to be wiped.
     */
    suspend fun buildSpec(server: Server, node: Node, sqlClient: SqlClient): ManagedPluginSpec? {
        val jarUrl = managedPluginJarResolver.resolve(server.type) ?: return null
        val targetDir = ManagedPluginJarResolver.targetDirOf(server.type)
        val configPath = ManagedPluginJarResolver.configPathOf(server.type) ?: return null

        val address = resolveAddress(node)

        val subject = server.id.toString()

        tokenProvider.invalidateTokensBySubjectAndType(subject, ServerAuthenticationTokenType, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(subject, ServerAuthenticationTokenType)

        tokenProvider.saveToken(token, subject, ServerAuthenticationTokenType, expireDate, sqlClient)

        return ManagedPluginSpec(
            jarUrl = jarUrl,
            targetDir = targetDir,
            configPath = configPath,
            config = ManagedPluginConfig(
                host = address.host,
                port = address.port,
                ssl = address.ssl,
                token = token,
                encryptionKey = server.aesKey
            ),
            // Fabric API for the Fabric build: without it the server crashes on every start.
            dependencies = managedPluginDependencyResolver.resolve(server)
        )
    }

    /** One line about what was decided, for the install task's message. */
    fun describe(server: Server, spec: ManagedPluginSpec?): String = when {
        spec != null && spec.dependencies.isNotEmpty() ->
            "Installing the Pano plugin with ${spec.dependencies.joinToString(", ") { it.name }}"

        spec != null -> "Installing the Pano plugin"
        ManagedPluginJarResolver.platformOf(server.type) == null ->
            "${server.type.name.lowercase()} has no Pano plugin; this server will not be linked"

        else -> "The Pano plugin could not be downloaded; link this server with /pano connect"
    }

    /**
     * Where the plugin inside this server should call Pano.
     *
     * `website-url` is the address an operator already keeps correct, so it is the answer whenever
     * it parses. When it does not, a node on this same machine can still reach Pano on loopback,
     * which is why the fallback exists at all — for a remote node it is logged as the guess it is.
     */
    private fun resolveAddress(node: Node): PlatformAddress {
        val fallback = PlatformAddress(
            host = LOOPBACK_HOST,
            port = configManager.config.server.httpPort,
            ssl = false
        )

        // A LOCAL node runs on the same machine as Pano, so loopback is always right and never
        // depends on DNS, a reverse proxy, TLS termination or a dev tunnel (an ngrok website-url
        // sent the plugin to a dead host in testing). Only remote nodes need the public address.
        if (node.kind == NodeKind.LOCAL) {
            return fallback
        }

        val websiteUrl = configManager.config.websiteUrl.trim()

        parse(websiteUrl)?.let { return it }

        if (node.kind != NodeKind.LOCAL) {
            logger.warn(
                "website-url is not a usable URL, so the plugin on node ${node.id} was pointed at " +
                    "${fallback.host}:${fallback.port}, which that host probably cannot reach."
            )
        }

        return fallback
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"

        /** Host, port and scheme the plugin's `platform` block needs. */
        data class PlatformAddress(val host: String, val port: Int, val ssl: Boolean)

        /**
         * Reads a `website-url` into a host, a port and a scheme.
         *
         * Pure, so the port defaulting — the part that is easy to get wrong and impossible to see
         * in a running install — can be asserted directly.
         */
        fun parse(websiteUrl: String?): PlatformAddress? {
            val trimmed = websiteUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

            val uri = try {
                URI(withScheme)
            } catch (_: Exception) {
                return null
            }

            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val ssl = uri.scheme.equals("https", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (ssl) 443 else 80

            return PlatformAddress(host, port, ssl)
        }
    }
}
