package com.panomc.platform.node

import com.panomc.platform.db.model.Server
import com.panomc.platform.node.message.ManagedPluginDependency
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The mods the Pano plugin needs next to it on a managed server.
 *
 * Only the Fabric build has any: it registers its commands, lifecycle and connection hooks through
 * Fabric API and declares `"fabric-api": "*"`, so a Fabric (or Quilt) server that gets the Pano mod
 * and no Fabric API refuses to start. Pano resolves the Fabric API build for the server's own
 * Minecraft version from Modrinth -- the same catalogue the plugin installer uses -- and hands the
 * node its download; the node skips it when the server already has Fabric API (a modpack usually
 * ships it).
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedPluginDependencyResolver(
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val logger: Logger
) {
    /** What [server] needs installed beside the Pano plugin; empty for every non-Fabric platform. */
    suspend fun resolve(server: Server): List<ManagedPluginDependency> {
        val type = server.type

        if (ManagedPluginJarResolver.platformOf(type) != FABRIC_PLATFORM) {
            return emptyList()
        }

        val gameVersion = server.softwareVersion?.trim()?.takeIf { it.isNotEmpty() }
        val loaders = PluginLoaderMapping.modrinthLoaders(type)

        val versions = gameVersion
            ?.let { pluginSourceCatalog.modrinthVersionsFor(FABRIC_API_PROJECT, loaders, it) }
            .orEmpty()

        val picked = pick(versions, gameVersion, loaders)

        if (picked == null) {
            logger.warn(
                "No Fabric API build for Minecraft ${gameVersion ?: "(unknown)"} was found on Modrinth; server " +
                    "${server.id} gets the Pano mod only if it already has Fabric API."
            )

            return listOf(ManagedPluginDependency(modId = FABRIC_API_MOD_ID, name = FABRIC_API_NAME))
        }

        val (_, file) = picked

        return listOf(
            ManagedPluginDependency(
                modId = FABRIC_API_MOD_ID,
                name = FABRIC_API_NAME,
                downloadUrl = file.url,
                filename = file.filename,
                sha512 = file.sha512,
                sha1 = file.sha1
            )
        )
    }

    companion object {
        const val FABRIC_PLATFORM = "fabric"

        /** Modrinth slug of Fabric API; the API accepts a slug wherever it takes a project id. */
        const val FABRIC_API_PROJECT = "fabric-api"

        /** The id in Fabric API's `fabric.mod.json`, and the one Quilted Fabric API provides. */
        const val FABRIC_API_MOD_ID = "fabric-api"

        const val FABRIC_API_NAME = "Fabric API"

        /**
         * The newest build published for exactly [gameVersion] on one of [loaders], a release when
         * there is one, and its downloadable primary file.
         *
         * Exact on purpose. The catalogue calls a build for `26.1` compatible with a `26.1.2` server
         * because most plugins run across a line, but Fabric API is compiled against one Minecraft
         * release and a build for another one is exactly the crash this exists to prevent.
         */
        fun pick(
            versions: List<PluginVersionData>,
            gameVersion: String?,
            loaders: List<String>
        ): Pair<PluginVersionData, PluginVersionFileData>? {
            val version = gameVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val candidates = versions.filter { candidate ->
                version in candidate.gameVersions &&
                    (loaders.isEmpty() || candidate.loaders.any { it in loaders }) &&
                    candidate.files.any { !it.url.isNullOrBlank() }
            }

            val chosen = candidates.firstOrNull { it.channel == "release" } ?: candidates.firstOrNull() ?: return null

            val file = chosen.files.firstOrNull { it.primary && !it.url.isNullOrBlank() }
                ?: chosen.files.first { !it.url.isNullOrBlank() }

            return chosen to file
        }
    }
}
