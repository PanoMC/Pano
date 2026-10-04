package com.panomc.platform.server.software

import io.vertx.core.json.JsonObject

/**
 * What a reinstall carries from the old server directory into the new one (SM-66, §2.4.31).
 *
 * Three switches, because those are the three things an operator thinks in: the worlds (every
 * directory with a `level.dat`, plus `world*`), the plugins or mods, and the configuration files.
 * The node copies exactly what is switched on; everything else is a fresh install.
 */
data class SoftwareChangeKeep(
    val worlds: Boolean,
    val plugins: Boolean,
    val configs: Boolean
) {
    fun toJson(): JsonObject = JsonObject()
        .put(WORLDS, worlds)
        .put(PLUGINS, plugins)
        .put(CONFIGS, configs)

    /** The switches that are on here but off in [allowed], by name, for `KEEP_INCOMPATIBLE`. */
    fun disallowedBy(allowed: SoftwareChangeKeep): List<String> = listOfNotNull(
        WORLDS.takeIf { worlds && !allowed.worlds },
        PLUGINS.takeIf { plugins && !allowed.plugins },
        CONFIGS.takeIf { configs && !allowed.configs }
    )

    /** This keep with every switch [allowed] forbids turned off. */
    fun clampTo(allowed: SoftwareChangeKeep) = SoftwareChangeKeep(
        worlds = worlds && allowed.worlds,
        plugins = plugins && allowed.plugins,
        configs = configs && allowed.configs
    )

    companion object {
        const val WORLDS = "worlds"
        const val PLUGINS = "plugins"
        const val CONFIGS = "configs"

        /**
         * What may be carried from [from] to [to] at all.
         *
         * - worlds: only between two backends; a proxy has none and cannot run one.
         * - plugins: only within one family, and only one that has a plugin directory (vanilla has
         *   none). A Paper plugin does not load on Velocity and a Fabric mod does not load on Paper.
         * - configs: within one family, or between Bukkit, Fabric and vanilla, which all read the
         *   vanilla files (`server.properties`, whitelist, ops, bans, usercache) — the node then
         *   carries only those.
         *
         * [nodeKeepsEverything] is false for a node older than the protocol that understands
         * `spec.keep` ([com.panomc.platform.node.NodeProtocol.REINSTALL_KEEP_VERSION]): such a node
         * carries the `world*` folders and nothing else, whatever Pano asks for, so offering plugins
         * or configs would be a promise nobody keeps.
         */
        fun allowed(
            from: ServerSoftwareFamily,
            to: ServerSoftwareFamily,
            nodeKeepsEverything: Boolean = true
        ): SoftwareChangeKeep {
            val sameFamily = from == to

            return SoftwareChangeKeep(
                worlds = !from.isProxy && !to.isProxy,
                plugins = nodeKeepsEverything && sameFamily && from.hasPlugins,
                configs = nodeKeepsEverything && (sameFamily || (from.sharesVanillaConfig && to.sharesVanillaConfig))
            )
        }

        /** Refused because one side is a proxy, which runs no world. */
        const val REASON_PROXY = "PROXY"

        /** Refused because the families differ (a Paper plugin does not load on Velocity). */
        const val REASON_FAMILY_CHANGED = "FAMILY_CHANGED"

        /** Refused because the software has no plugin or mod directory (vanilla). */
        const val REASON_NO_PLUGINS = "NO_PLUGINS"

        /** Refused only because the node predates `spec.keep`; updating the node lifts it. */
        const val REASON_NODE_TOO_OLD = "NODE_TOO_OLD"

        /**
         * Why each switch [allowed] turns off is off, null for the ones it allows — what the
         * panel's tooltip says. The structural reason wins over the node's age: a plugin that
         * cannot run on the new software is not fixed by updating the node.
         */
        fun reasons(
            from: ServerSoftwareFamily,
            to: ServerSoftwareFamily,
            nodeKeepsEverything: Boolean = true
        ): Map<String, String?> {
            val sameFamily = from == to
            val nodeReason = REASON_NODE_TOO_OLD.takeIf { !nodeKeepsEverything }

            return mapOf(
                WORLDS to REASON_PROXY.takeIf { from.isProxy || to.isProxy },
                PLUGINS to when {
                    !sameFamily -> REASON_FAMILY_CHANGED
                    !from.hasPlugins -> REASON_NO_PLUGINS
                    else -> nodeReason
                },
                CONFIGS to when {
                    !sameFamily && !(from.sharesVanillaConfig && to.sharesVanillaConfig) -> REASON_FAMILY_CHANGED
                    else -> nodeReason
                }
            )
        }

        /**
         * What the panel preselects: everything that is allowed. A change inside one family keeps
         * the whole server; a change across families keeps whatever still makes sense, which the
         * rules in [allowed] already describe.
         */
        fun defaults(
            from: ServerSoftwareFamily,
            to: ServerSoftwareFamily,
            nodeKeepsEverything: Boolean = true
        ): SoftwareChangeKeep = allowed(from, to, nodeKeepsEverything)

        /**
         * Reads a `keep` object from a request body, filling each missing switch from [defaults].
         * Null when the body has no `keep` at all.
         */
        fun parse(json: JsonObject?, defaults: SoftwareChangeKeep): SoftwareChangeKeep? {
            json ?: return null

            return SoftwareChangeKeep(
                worlds = json.getBoolean(WORLDS, defaults.worlds),
                plugins = json.getBoolean(PLUGINS, defaults.plugins),
                configs = json.getBoolean(CONFIGS, defaults.configs)
            )
        }
    }
}
