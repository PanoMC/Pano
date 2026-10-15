package com.panomc.platform.plugin

import com.panomc.platform.api.PanoPlugin
import io.vertx.core.json.JsonObject

/**
 * The one backend function for a plugin's namespace (doc 01 §1): the `namespace` the build wrote
 * into the plugin's `pano-plugin.json`, else the plugin id minus a leading `pano-plugin-` (nothing
 * else is stripped). Views, controllers, classes, lang folders, URL targets, widget tags and
 * webhook events all use this token; two plugins with the same one are a clash, never a rename.
 */
object PluginNamespace {
    const val ID_PREFIX = "pano-plugin-"

    /** The namespace rule without a package: the id minus a leading [ID_PREFIX]. */
    fun fromId(pluginId: String): String {
        val stripped = pluginId.removePrefix(ID_PREFIX)

        return stripped.ifEmpty { pluginId }
    }

    /** [written] is the `namespace` of `pano-plugin.json` (or null when the package has none). */
    fun of(pluginId: String, written: String?): String = written?.trim()?.takeIf { it.isNotEmpty() } ?: fromId(pluginId)

    /** [manifest] is the parsed `pano-plugin.json` of the plugin, or null when it has none. */
    fun ofManifest(pluginId: String, manifest: JsonObject?): String =
        of(pluginId, manifest?.getValue("namespace") as? String)

    /** The namespace of [plugin], read from its built package (null manifest = the id rule). */
    fun of(plugin: PanoPlugin): String {
        val manifest = try {
            plugin.pluginUiManager.manifest(plugin)
        } catch (e: Exception) {
            null
        }

        return ofManifest(plugin.pluginId, manifest)
    }
}
