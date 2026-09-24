package com.panomc.platform.server.feature

import com.panomc.platform.db.model.Server
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.ServerNoStdin
import com.panomc.platform.Main
import com.panomc.platform.node.AgentNodeDirectory
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodeJarProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.NodeUpdateAvailability
import com.panomc.platform.node.NodeUpdateProgressStore
import com.panomc.platform.server.ServerActiveTaskStore
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.server.ServerStopReasonStore
import com.panomc.platform.server.plugins.PanoPluginStatus
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan
import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Decides, per feature, who does the job for one server (SM-52, §2.4.17).
 *
 * The rule the whole plan turns on: every feature works with whatever is there. A server with only
 * a node, a server with only a plugin, and a server with both are all served — when both exist
 * Pano picks the source that does the job *better*, and a plugin too old to announce a capability
 * simply contributes less rather than breaking the page. The panel stops asking "is this managed?"
 * and "does it have capability X?" and asks `features` instead.
 *
 * The table itself lives in the companion object and is a pure function of [ServerFeatureInputs].
 * That is the point: this is the one place in the server feature where a wrong answer is invisible
 * — a control that looks enabled and silently does nothing, or one greyed out while a perfectly
 * capable node sits behind it — so it is written as a table and tested as a table, with no socket,
 * no database and no Spring context anywhere near it. The bean exists only to fetch the two
 * liveness facts the row does not carry.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerFeatureResolver(
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val serverStopReasonStore: ServerStopReasonStore,
    private val activeTaskStore: ServerActiveTaskStore,
    private val agentNodeDirectory: AgentNodeDirectory,
    private val nodeJarProvider: NodeJarProvider,
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val nodeUpdateProgressStore: NodeUpdateProgressStore
) {
    /** Reads the live facts about [server] and resolves its features. */
    fun resolve(server: Server): ServerFeatures = resolve(inputsOf(server))

    /** The inputs [server] resolves from, exposed so a caller can reuse one of the facts. */
    fun inputsOf(server: Server): ServerFeatureInputs = ServerFeatureInputs.of(
        server = server,
        nodeConnected = server.nodeId?.let { nodeManager.isConnected(it) } == true,
        pluginConnected = serverManager.isConnected(server.id)
    )

    /**
     * [server]'s public JSON with `features` attached, which is how every panel view gets it.
     *
     * `lastStopReason` rides along for the same reason: it is not a column, and this is the one
     * place every server JSON the panel reads passes through — `{ reason, reasonCode, javaMajor,
     * at }` for the last stop the node explained, null otherwise (SM-63). `activeTask` likewise:
     * `{ id, uuid, kind, status, percent, message, startedAt }` of the newest PENDING/RUNNING task
     * on the server, null when it is idle (SM-68).
     *
     * `agent` and `agentInfo` say whether the node behind the server is a Pano Agent -- a node
     * dedicated to this one server, which the panel shows as "Pano Agent" and never as a node:
     * `agentInfo` is `{ nodeId, version, latestVersion, protocolVersion, updateAvailable, online, host,
     * os, arch }` for such a server and null otherwise; `latestVersion` is the daemon this Pano hands
     * out, null for a development build (SM-77).
     *
     * `daemonUpdate` is the update of the daemon behind the server while one is under way or just
     * ended (SM-77): `{ kind: "agent" | "node", nodeId, nodeName, version, status, percent, message }`
     * with `status` one of RUNNING, RESTARTING, DONE, FAILED ([NodeUpdateProgressStore]); null
     * otherwise. `activeTask.panoPluginUpdate` says whether the running task is the Pano plugin
     * updating itself.
     */
    fun toPublicJsonObject(server: Server): JsonObject =
        server.toPublicJsonObject()
            .put("features", resolve(server).toJsonObject())
            .put("lastStopReason", serverStopReasonStore.get(server.id)?.toJsonObject())
            .put("activeTask", activeTaskStore.get(server.id)?.toJsonObject())
            .apply {
                val agentInfo = agentInfoOf(server)

                put("agent", agentInfo != null)
                put("agentInfo", agentInfo)
                put("panoPluginUpdate", panoPluginUpdateOf(server))
                put("nodeOutdated", nodeOutdatedOf(server))
                put("daemonUpdate", nodeUpdateProgressStore.get(server.nodeId)?.toServerJsonObject())
            }

    /**
     * Whether the Pano plugin in [server] wants replacing, for the one-click update in the server's
     * header — on every server page, not only the Overview's plugin row. Null for software that
     * runs no Pano plugin.
     *
     * `available` is the version comparison (null while either side is unknown, never a guess);
     * `outdatedProtocol` is the plugin that is connected right now and speaks an older protocol
     * than this Pano, which is outdated whatever its version string says. `mode` and `manual` are
     * what `POST .../pano-plugin/update` would do: the node or the plugin itself, or the admin by
     * hand (the same decisions as `PanoPluginUpdateService`).
     */
    private fun panoPluginUpdateOf(server: Server): JsonObject? {
        if (ManagedPluginJarResolver.platformOf(server.type) == null) {
            return null
        }

        val pluginConnected = serverManager.isConnected(server.id)
        val latest = managedPluginJarResolver.latestVersionOrWarm(server.type)

        val mode = PanoPluginUpdatePlan.modeFor(
            managed = server.isManaged && server.nodeId != null && server.uuid != null,
            nodeConnected = server.nodeId?.let { nodeManager.isConnected(it) } == true,
            pluginConnected = pluginConnected,
            pluginCanSelfUpdate = server.hasCapability(ServerCapability.SELF_UPDATE)
        )

        val refusal = if (mode != null) null else PanoPluginUpdatePlan.refusalFor(
            type = server.type,
            managed = server.isManaged,
            pluginConnected = pluginConnected
        )

        return JsonObject()
            .put("available", PanoPluginStatus.updateAvailable(server.pluginVersion, latest))
            .put("outdatedProtocol", pluginConnected && server.protocolVersion < ServerProtocol.CURRENT_PROTOCOL_VERSION)
            .put("latestVersion", latest)
            .put("mode", mode?.wire)
            .put("manual", refusal != null && PanoPluginUpdatePlan.canUpdateByHand(refusal, server.isManaged))
    }

    /**
     * Whether the node (or Pano Agent) running [server] is connected and speaks an older protocol
     * than this Pano, so some of what the panel offers for the server does not work until it is
     * updated. False when there is no node, or it is offline: an offline node says nothing new.
     */
    private fun nodeOutdatedOf(server: Server): Boolean {
        val node = server.nodeId?.let { nodeManager.getConnectedNodeById(it) } ?: return false

        return node.protocolVersion < NodeProtocol.VERSION
    }

    /** What the panel shows about the Pano Agent behind [server], or null when there is none. */
    private fun agentInfoOf(server: Server): JsonObject? {
        val stored = agentNodeDirectory.get(server.nodeId) ?: return null

        // The connected instance carries what the last hello said; the stored copy is the fallback.
        val node = nodeManager.getConnectedNodeById(stored.id) ?: stored

        return JsonObject()
            .put("nodeId", node.id)
            .put("version", node.version)
            .put("latestVersion", NodeInstallScriptProvider.releaseVersion())
            .put("protocolVersion", node.protocolVersion)
            .put(
                "updateAvailable",
                NodeUpdateAvailability.isAvailable(
                    nodeVersion = node.version,
                    platformVersion = Main.VERSION,
                    nodeJarSha256 = nodeManager.getJarSha256(node.id),
                    servedSha256 = nodeJarProvider.cachedSha256()
                )
            )
            .put("online", nodeManager.isConnected(node.id))
            .put("host", node.hostname ?: node.remoteAddress)
            .put("os", node.os)
            .put("arch", node.arch)
    }

    /**
     * The source that will serve [feature] for [server], or an error the panel can explain.
     *
     * This is the one call every feature endpoint makes before it does anything else, so a request
     * for something nothing can do is refused in one place instead of failing differently in nine.
     */
    fun pick(server: Server, feature: ServerFeature): ServerFeatureSource =
        pick(inputsOf(server), feature)

    companion object {
        private val NODE = ServerFeatureSource.NODE
        private val PLUGIN = ServerFeatureSource.PLUGIN
        private val PANO = ServerFeatureSource.PANO

        /**
         * The preference table of §2.4.17 A, first available source wins.
         *
         * | feature              | order                                                              |
         * |----------------------|--------------------------------------------------------------------|
         * | console.stream       | node > plugin(console) > pano (its own ring buffer)                |
         * | console.history      | node > plugin(console) > pano                                      |
         * | console.input        | node (stdin available) > plugin(commands)                          |
         * | power.start / kill   | node only                                                          |
         * | power.stop / restart | node (process alive) > plugin(power)                               |
         * | metrics.tps          | plugin(metrics) only, never on a proxy — nothing else counts ticks |
         * | metrics.memory       | plugin(metrics, JVM heap) > node (process RSS)                     |
         * | metrics.players      | plugin(metrics) > node (server list ping)                          |
         * | players.list         | plugin(players, `full`) > node (SLP sample, `sample`)              |
         * | players.actions      | plugin(commands/players) > node (stdin, commands composed by Pano) |
         * | plugins.list         | plugin(plugins, loaded state) > node (jar scan)                    |
         * | plugins.toggle       | plugin(plugins, Bukkit only) > node (rename, restart required)     |
         * | plugins.install      | node > plugin(plugin-install)                                      |
         * | plugins.identify     | node > plugin(plugin-install)                                      |
         * | files.*              | node > plugin(files)                                               |
         * | backups.create       | node > plugin(backups)                                             |
         * | backups.restore      | node (`live`, server stopped) > plugin(`next-start`)               |
         * | schedules.runner     | node > plugin(schedules) > pano                                    |
         */
        fun resolve(inputs: ServerFeatureInputs): ServerFeatures {
            val node = inputs.nodeAvailable

            // Every "can the plugin do X" question, asked once so the rest reads as a table.
            val console = inputs.has(ServerCapability.CONSOLE)
            val commands = inputs.has(ServerCapability.COMMANDS)
            val power = inputs.has(ServerCapability.POWER)
            val metrics = inputs.has(ServerCapability.METRICS)
            val players = inputs.has(ServerCapability.PLAYERS)
            val plugins = inputs.has(ServerCapability.PLUGINS)
            val files = inputs.has(ServerCapability.FILES)
            val backups = inputs.has(ServerCapability.BACKUPS)
            val pluginInstall = inputs.has(ServerCapability.PLUGIN_INSTALL)
            val schedules = inputs.has(ServerCapability.SCHEDULES)

            // The node reads console output from the log file, so it keeps working for a process
            // it adopted and for one that is not running at all; only writing needs the pipe.
            val consoleSource = first(NODE to node, PLUGIN to console, PANO to true)

            // A node with a live process can stop it; a node whose process is already down cannot,
            // and that is a different thing from a node that is not there at all.
            val nodePower = node && inputs.processAlive
            val nodeSample = node && inputs.processAlive

            // Restore is the one place where the better source is unavailable for a reason that is
            // not about capability: the node overwrites files in place and will not do that under
            // a running process, which is exactly when the plugin's next-start marker earns its
            // keep.
            val backupRestore = first(NODE to (node && inputs.processStopped), PLUGIN to backups)

            val playerList = first(PLUGIN to players, NODE to nodeSample)

            val features = ServerFeatures(
                console = ServerFeatures.Console(
                    stream = consoleSource,
                    history = consoleSource,
                    input = first(NODE to (node && inputs.stdinAvailable), PLUGIN to commands)
                ),
                power = ServerFeatures.Power(
                    start = node && !inputs.processAlive,
                    stop = first(NODE to nodePower, PLUGIN to power),
                    restart = first(NODE to nodePower, PLUGIN to power),
                    kill = nodePower
                ),
                metrics = ServerFeatures.Metrics(
                    // A proxy's plugin announces `metrics` for its memory and players, but a
                    // proxy runs no world and so has no tick to count (§2.4.35 NOT_SUPPORTED).
                    tps = first(PLUGIN to (metrics && !inputs.proxy)),
                    memory = first(PLUGIN to metrics, NODE to nodeSample),
                    players = first(PLUGIN to metrics, NODE to nodeSample),
                    host = node
                ),
                players = ServerFeatures.Players(
                    list = playerList,
                    listQuality = when (playerList) {
                        PLUGIN -> ServerFeatures.Players.QUALITY_FULL
                        NODE -> ServerFeatures.Players.QUALITY_SAMPLE
                        else -> null
                    },
                    // Either capability will do on the plugin path: kick and message go through
                    // its player API, everything else is a console command it dispatches.
                    actions = first(
                        PLUGIN to (commands || players),
                        NODE to (nodeSample && inputs.stdinAvailable)
                    )
                ),
                // Vanilla has nowhere to put a plugin, so the whole group is off whoever is asking.
                plugins = ServerFeatures.Plugins(
                    list = first(PLUGIN to (plugins && inputs.supportsPlugins), NODE to (node && inputs.supportsPlugins)),
                    toggle = first(
                        PLUGIN to (plugins && inputs.bukkitFamily),
                        NODE to (node && inputs.supportsPlugins)
                    ),
                    install = first(NODE to (node && inputs.supportsPlugins), PLUGIN to pluginInstall),
                    identify = first(NODE to (node && inputs.supportsPlugins), PLUGIN to pluginInstall)
                ),
                files = ServerFeatures.Files(
                    source = first(NODE to node, PLUGIN to files),
                    transfer = first(NODE to node, PLUGIN to files)
                ),
                backups = ServerFeatures.Backups(
                    create = first(NODE to node, PLUGIN to backups),
                    restore = backupRestore,
                    restoreMode = when (backupRestore) {
                        NODE -> ServerFeatures.Backups.MODE_LIVE
                        PLUGIN -> ServerFeatures.Backups.MODE_NEXT_START
                        else -> null
                    }
                ),
                // Pano's own clock is always there, so a server with neither a node nor an
                // agent-lite plugin still keeps its schedules -- which is every linked server
                // today.
                schedules = ServerFeatures.Schedules(
                    runner = first(NODE to node, PLUGIN to schedules, PANO to true)
                )
            )

            // Every entry that came out empty is explained right here, from the same inputs, so
            // the reason can never disagree with the answer it explains (SM-70, §2.4.35).
            val reasons = ServerFeatures.ENTRY_IDS
                .filterNot { features.isAvailable(it) }
                .mapNotNull { id -> explain(inputs, id)?.let { id to it } }
                .toMap()

            return features.copy(reasons = reasons)
        }

        /**
         * Why the entry at [id] is unavailable for [inputs], assuming it is (SM-70, §2.4.35).
         *
         * Checked in this order, first match wins — each step is what would make the ones after it
         * true, so it is the sentence worth saying:
         *
         * 1. `NOT_SUPPORTED` for what the software itself lacks (plugins on Vanilla; ticks on a proxy
         *    or on software no Pano plugin exists for). A fact about the software outranks every
         *    state, because no state change would bring the feature back.
         * 2. `NODE_OFFLINE` — managed, node not connected. Everything below is a consequence of it.
         * 3. `NODE_ONLY` — linked, and only a node could ever do it (start, kill, host metrics).
         * 4. `SERVER_STOPPED` — it needs the live game (console input, power, metrics, players) and
         *    the process (managed) or the plugin (linked) is not up.
         * 5. `SERVER_RUNNING` — a backup restore the node would do, once the process is stopped.
         * 6. `NO_STDIN` — the command path the node lost when it adopted the process (§2.4.16).
         * 7. `PLUGIN_NOT_CONNECTED`, `PLUGIN_OUTDATED`, `PLUGIN_LACKS` — the plugin is the only
         *    source left, and it is away, too old to announce anything, or does not announce the
         *    capability. All three carry that capability.
         * 8. `NOT_SUPPORTED` — whatever is left: the plugin offers the capability and still cannot
         *    (a plugin toggle outside the Bukkit family, with no node to rename the jar instead).
         *
         * Returns `null` only for `power.start` on a process that is already alive: that is not
         * an unavailable feature but one that has nothing left to do.
         */
        fun explain(inputs: ServerFeatureInputs, id: String): ServerFeatureUnavailability? {
            val group = id.substringBefore('.')
            val capability = PLUGIN_CAPABILITY[id]

            if (group == "plugins" && !inputs.supportsPlugins) {
                return unavailable(ServerFeatureReason.NOT_SUPPORTED)
            }

            if (id == ServerFeature.METRICS_TPS.id && (inputs.proxy || !inputs.pluginModule)) {
                return unavailable(ServerFeatureReason.NOT_SUPPORTED)
            }

            if (inputs.kind == ServerKind.MANAGED && !inputs.nodeConnected) {
                return unavailable(ServerFeatureReason.NODE_OFFLINE)
            }

            if (capability == null) {
                return when {
                    inputs.kind != ServerKind.MANAGED -> unavailable(ServerFeatureReason.NODE_ONLY)
                    // A node-only entry on a connected node is only ever off because of the
                    // process: start while it runs (nothing to do), kill while it does not.
                    id == ServerFeatures.POWER_START -> null
                    else -> unavailable(ServerFeatureReason.SERVER_STOPPED)
                }
            }

            if (id in RUNTIME_ENTRIES && !inputs.serverUp) {
                return unavailable(ServerFeatureReason.SERVER_STOPPED)
            }

            if (id == ServerFeature.BACKUPS_RESTORE.id && inputs.nodeAvailable && !inputs.processStopped) {
                return unavailable(ServerFeatureReason.SERVER_RUNNING)
            }

            if (id in STDIN_ENTRIES && inputs.nodeAvailable && !inputs.stdinAvailable) {
                return unavailable(ServerFeatureReason.NO_STDIN)
            }

            return when {
                !inputs.pluginConnected -> unavailable(ServerFeatureReason.PLUGIN_NOT_CONNECTED, capability)
                inputs.pluginLegacy -> unavailable(ServerFeatureReason.PLUGIN_OUTDATED, capability)
                !inputs.has(capability) -> unavailable(ServerFeatureReason.PLUGIN_LACKS, capability)
                else -> unavailable(ServerFeatureReason.NOT_SUPPORTED)
            }
        }

        /**
         * [resolve] narrowed to one feature, refusing instead of returning `null`.
         *
         * The refusal is `409 FEATURE_UNAVAILABLE { feature, reason, capability? }` — the reason
         * [explain] gave the entry in `features.reasons`, so the toast after a refused request is
         * the sentence the page's notice already shows (SM-70, §2.4.35).
         *
         * [ServerNoStdin] survives as its own code for the one case it was written for (SM-51): a
         * managed server whose process the node adopted has a console that reads but cannot be
         * written to, and "restart it from the panel" is a different sentence from "nothing here
         * can do this". It carries the same extras, with `reason: NO_STDIN`.
         */
        fun pick(inputs: ServerFeatureInputs, feature: ServerFeature): ServerFeatureSource {
            val features = resolve(inputs)
            val source = features.sourceOf(feature)

            if (source != null) {
                return source
            }

            // The same reason `features.reasons` carries, so a refusal and the notice that should
            // have prevented it say one thing. Every source entry has one; the fallback is only
            // for the compiler.
            val why = features.reasons[feature.id]
                ?: unavailable(ServerFeatureReason.NOT_SUPPORTED)

            if (feature == ServerFeature.CONSOLE_INPUT && why.reason == ServerFeatureReason.NO_STDIN) {
                throw ServerNoStdin(extras = why.toErrorExtras(feature.id))
            }

            throw FeatureUnavailable(extras = why.toErrorExtras(feature.id))
        }

        /**
         * The plugin capability that would serve each entry. An entry missing here is one only a
         * node can ever do; `console.stream`, `console.history` and `schedules.runner` are listed
         * for completeness, although Pano's own fallback means they are never unavailable.
         */
        private val PLUGIN_CAPABILITY: Map<String, ServerCapability> = mapOf(
            ServerFeature.CONSOLE_STREAM.id to ServerCapability.CONSOLE,
            ServerFeature.CONSOLE_HISTORY.id to ServerCapability.CONSOLE,
            ServerFeature.CONSOLE_INPUT.id to ServerCapability.COMMANDS,
            ServerFeature.POWER_STOP.id to ServerCapability.POWER,
            ServerFeature.POWER_RESTART.id to ServerCapability.POWER,
            ServerFeature.METRICS_TPS.id to ServerCapability.METRICS,
            ServerFeature.METRICS_MEMORY.id to ServerCapability.METRICS,
            ServerFeature.METRICS_PLAYERS.id to ServerCapability.METRICS,
            ServerFeature.PLAYERS_LIST.id to ServerCapability.PLAYERS,
            // `commands` would do as well; `players` is the one the Players page is about.
            ServerFeature.PLAYERS_ACTIONS.id to ServerCapability.PLAYERS,
            ServerFeature.PLUGINS_LIST.id to ServerCapability.PLUGINS,
            ServerFeature.PLUGINS_TOGGLE.id to ServerCapability.PLUGINS,
            ServerFeature.PLUGINS_INSTALL.id to ServerCapability.PLUGIN_INSTALL,
            ServerFeature.PLUGINS_IDENTIFY.id to ServerCapability.PLUGIN_INSTALL,
            ServerFeature.FILES_SOURCE.id to ServerCapability.FILES,
            ServerFeature.FILES_TRANSFER.id to ServerCapability.FILES,
            ServerFeature.BACKUPS_CREATE.id to ServerCapability.BACKUPS,
            ServerFeature.BACKUPS_RESTORE.id to ServerCapability.BACKUPS,
            ServerFeature.SCHEDULES_RUNNER.id to ServerCapability.SCHEDULES
        )

        /**
         * Entries that need the live game — the process, or the plugin inside it. Everything else
         * (a file listing, a backup, a jar scan) is disk work a node does on a stopped server too.
         */
        private val RUNTIME_ENTRIES: Set<String> = setOf(
            ServerFeature.CONSOLE_INPUT.id,
            ServerFeature.POWER_STOP.id,
            ServerFeature.POWER_RESTART.id,
            ServerFeature.METRICS_TPS.id,
            ServerFeature.METRICS_MEMORY.id,
            ServerFeature.METRICS_PLAYERS.id,
            ServerFeature.PLAYERS_LIST.id,
            ServerFeature.PLAYERS_ACTIONS.id
        )

        /** Entries the node serves by writing to the process's stdin. */
        private val STDIN_ENTRIES: Set<String> = setOf(
            ServerFeature.CONSOLE_INPUT.id,
            ServerFeature.PLAYERS_ACTIONS.id
        )

        private fun unavailable(reason: ServerFeatureReason, capability: ServerCapability? = null) =
            ServerFeatureUnavailability(reason, capability)

        private fun first(vararg candidates: Pair<ServerFeatureSource, Boolean>) =
            ServerFeatureSource.firstOf(*candidates)
    }
}
