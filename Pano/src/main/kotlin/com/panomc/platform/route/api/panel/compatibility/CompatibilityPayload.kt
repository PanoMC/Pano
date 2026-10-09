package com.panomc.platform.route.api.panel.compatibility

import com.panomc.platform.ApiLevel
import com.panomc.platform.api.ExternalUrlProvider
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.gate.CompatibilityReconciler
import com.panomc.platform.gate.InstalledResource
import com.panomc.platform.gate.ReconcileReport
import com.panomc.platform.gate.Verdict
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.node.LocalNodeJarLocator
import com.panomc.platform.node.NodeKind
import com.panomc.platform.route.ApiPaths
import org.slf4j.LoggerFactory

/**
 * The body of `GET /api/v1/panel/compatibility` and of the plan's `agents` (doc 04 section 7), computed from the
 * installed resources, the last reconcile report, the stored protocol versions and the plugins' address lists.
 * No table: nothing here is persisted.
 */
object CompatibilityPayload {
    private val logger = LoggerFactory.getLogger(CompatibilityPayload::class.java)

    /** Where a game server's plugin jar is downloaded by hand (an admin session, not the server's own token). */
    fun serverJarPath(serverId: Long) = ApiPaths.panel("/servers/$serverId/pano-plugin/jar")

    /** Where the node (or agent) jar is downloaded by hand. */
    fun nodeJarPath(agent: Boolean) =
        ApiPaths.core("/node/" + if (agent) LocalNodeJarLocator.AGENT_JAR_NAME else LocalNodeJarLocator.JAR_NAME)

    /**
     * Game servers and nodes whose stored protocol is below what this Pano talks to. Old jars cannot reach the new
     * paths (decision 46), so the row stays until the jar is replaced by hand and the agent reconnects on the new
     * protocol. A server nobody accepted yet is not listed.
     *
     * The local node is Pano's own: Pano replaces its jar and starts it again by itself, so it never asks for a jar by
     * hand. It is listed only when the operator pinned a jar of their own ([localNodeManaged] false).
     */
    fun agents(servers: List<Server>, nodes: List<Node>, localNodeManaged: Boolean = true): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()

        servers.filter { it.permissionGranted && it.protocolVersion < ApiLevel.MIN_MC_PROTOCOL }.forEach {
            rows += linkedMapOf(
                "type" to "SERVER",
                "id" to it.id,
                "name" to (it.customName ?: it.name),
                "protocolVersion" to it.protocolVersion,
                "minProtocolVersion" to ApiLevel.MIN_MC_PROTOCOL,
                "pluginVersion" to it.pluginVersion,
                "action" to "MANUAL_JAR",
                "downloadPath" to serverJarPath(it.id)
            )
        }

        nodes.filter {
            it.approved && it.protocolVersion < ApiLevel.MIN_NODE_PROTOCOL && !(localNodeManaged && it.kind == NodeKind.LOCAL)
        }.forEach {
            rows += linkedMapOf(
                "type" to if (it.agent) "AGENT" else "NODE",
                "id" to it.id,
                "name" to it.name,
                "protocolVersion" to it.protocolVersion,
                "minProtocolVersion" to ApiLevel.MIN_NODE_PROTOCOL,
                "pluginVersion" to it.version,
                "action" to "MANUAL_JAR",
                "downloadPath" to nodeJarPath(it.agent)
            )
        }

        return rows
    }

    /**
     * Whether Pano itself keeps the local node's jar current: the local node is on and nobody pinned a jar
     * (`local-node.jar-path`, `-Dpano.node.jar`).
     */
    fun localNodeManaged(config: PanoConfig, systemProperty: String? = System.getProperty("pano.node.jar")): Boolean {
        val local = config.localNode ?: return false

        return local.enabled && local.jarPath.isNullOrBlank() && systemProperty.isNullOrBlank()
    }

    /** The addresses the started plugins list, each with the plugin that listed it. A provider that throws is skipped. */
    fun externalUrls(providers: Map<String, List<ExternalUrlProvider>>): List<Map<String, Any?>> =
        providers.entries.sortedBy { it.key }.flatMap { (pluginId, list) ->
            list.flatMap { provider ->
                try {
                    provider.urls().map { linkedMapOf<String, Any?>("pluginId" to pluginId, "label" to it.label, "url" to it.url) }
                } catch (e: Exception) {
                    logger.error("External URL provider {} of '{}' failed; skipped", provider.javaClass.name, pluginId, e)

                    emptyList()
                }
            }
        }

    /** The `heldBy` object of the plugin payloads: the root-cause plugin, its verdict and the direct dependency. */
    fun heldByJson(pluginId: String, verdict: String, via: String, name: String?): Map<String, Any?> =
        linkedMapOf("pluginId" to pluginId, "name" to name, "verdict" to verdict, "via" to via)

    /** The refused plugins and themes, with what the last reconcile learned about each. */
    fun resources(installed: List<InstalledResource>, report: ReconcileReport): List<Map<String, Any?>> =
        installed.filter { (it.verdict != Verdict.OK || it.heldBy != null) && !it.disabledByAdmin }.map { resource ->
            val entry = report.entries.firstOrNull { it.id == resource.id && it.type == resource.type }

            linkedMapOf(
                "id" to resource.id,
                "type" to resource.type.name,
                "title" to resource.title,
                "version" to resource.version,
                "apiLevel" to resource.apiLevel,
                "verdict" to resource.verdict.name,
                "hasCompatibleUpdate" to (entry?.hasCompatibleUpdate ?: false),
                "lastError" to entry?.lastError,
                // A compatible plugin held back by a refused required dependency: null for every other resource.
                "heldBy" to resource.heldBy?.let { heldByJson(it.pluginId, it.verdict.name, it.via, it.name) }
            )
        }

    fun build(
        reconciler: CompatibilityReconciler,
        servers: List<Server>,
        nodes: List<Node>,
        externalUrls: Map<String, List<ExternalUrlProvider>>,
        localNodeManaged: Boolean = true
    ): Map<String, Any?> {
        val report = reconciler.report

        return linkedMapOf(
            "apiLevel" to linkedMapOf("min" to ApiLevel.MIN_SUPPORTED, "current" to ApiLevel.CURRENT),
            "resources" to resources(reconciler.installed(), report),
            "agents" to agents(servers, nodes, localNodeManaged),
            "externalUrls" to externalUrls(externalUrls),
            "reconcile" to linkedMapOf(
                "running" to reconciler.running,
                "ranAt" to report.ranAt,
                "storeReachable" to report.storeReachable,
                "installed" to report.installed.map {
                    linkedMapOf(
                        "id" to it.id,
                        "type" to it.type.name,
                        "fromVersion" to it.version,
                        "version" to it.installedVersion,
                        "restartRequired" to it.restartRequired
                    )
                }
            )
        )
    }
}
