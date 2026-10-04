package com.panomc.platform.node

import com.panomc.platform.node.dto.JavaRuntimeData
import com.panomc.platform.node.dto.NodeResources
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * What `GET /api/panel/nodes/:id/java` answers, built out of a node's `JAVA_CATALOG` reply or out of
 * the runtimes Pano has stored when there is no reply to be had (SM-63, §2.4.28).
 *
 * Every answer has the same shape, so the panel's Java card never has to ask which kind it got:
 *
 * ```
 * { online, supported, os, arch, libc, autoDownload, catalogError,
 *   runtimes: [{ major, version, vendor, path, managed, usedBy: [{ id, name, uuid }] }],
 *   downloadable: [{ major, available, vendor, version, size, installedVersion, updateAvailable }] }
 * ```
 *
 * `online` is whether the node is connected; `supported` whether it understands the Java messages
 * at all (install buttons are hidden when it does not). `downloadable` is empty whenever the list
 * did not come from the node itself, with `catalogError` saying why when there is a reason worth
 * showing.
 *
 * Kept free of Spring and the socket so the mapping — the part with the untrusted input — can be
 * tested on plain JSON. The reply comes from a separate process on somebody else's machine, so
 * every list is capped, every string clamped, and a server uuid only turns into a server when it
 * is one of this node's.
 */
object NodeJavaCatalog {
    /** A Pano server a runtime is used by, as the panel links to it. */
    data class ServerRef(val id: Long, val name: String)

    /** Majors Pano accepts in an install or removal request. */
    val MAJOR_RANGE = 1..99

    /** The characters a full Java version may have (`21.0.12+7`, `1.8.0_462`, `17.0.16-LTS`). */
    private val VERSION_PATTERN = Regex("^[A-Za-z0-9._+-]{1,64}$")

    const val MAX_DOWNLOADABLE = 32
    const val MAX_USED_BY = 64
    private const val MAX_SHORT_FIELD = 64
    private const val MAX_ERROR_LENGTH = 500

    /** `catalogError` when a node that should answer did not do so in time. */
    const val ERROR_TIMEOUT = "TIMEOUT"

    fun isValidMajor(major: Int?): Boolean = major != null && major in MAJOR_RANGE

    fun isValidVersion(version: String?): Boolean = version != null && VERSION_PATTERN.matches(version)

    /**
     * The answer built from a node's reply.
     *
     * A reply with `ok: false` is not an error for the panel: the node exists, is connected and
     * speaks the protocol, it just could not produce a catalog, so the stored runtimes are shown
     * with the node's reason as `catalogError`.
     */
    fun fromReply(
        reply: JsonObject,
        stored: NodeResources,
        serversByUuid: Map<String, ServerRef>,
        os: String?,
        arch: String?
    ): JsonObject {
        if (reply.getValue("ok") == false) {
            return fallback(
                stored = stored,
                online = true,
                supported = true,
                os = os,
                arch = arch,
                catalogError = clampError(reply.getValue("error") as? String) ?: "UNKNOWN"
            )
        }

        val runtimes = (reply.getValue("runtimes") as? JsonArray ?: JsonArray())
            .take(NodeResources.MAX_JAVA_RUNTIMES)
            .mapNotNull { entry ->
                val json = entry as? JsonObject ?: return@mapNotNull null
                val runtime = JavaRuntimeData.fromJson(json) ?: return@mapNotNull null

                runtimeJson(runtime, usedBy(json.getValue("usedBy") as? JsonArray, serversByUuid))
            }

        val downloadable = (reply.getValue("downloadable") as? JsonArray ?: JsonArray())
            .take(MAX_DOWNLOADABLE)
            .mapNotNull { downloadableJson(it as? JsonObject) }

        return JsonObject()
            .put("online", true)
            .put("supported", true)
            .put("os", shortField(reply.getValue("os")) ?: os)
            .put("arch", shortField(reply.getValue("arch")) ?: arch)
            .put("libc", shortField(reply.getValue("libc")))
            .put("autoDownload", reply.getValue("autoDownload") as? Boolean ?: stored.javaAutoDownload)
            .put("catalogError", clampError(reply.getValue("catalogError") as? String))
            .put("runtimes", JsonArray(runtimes))
            .put("downloadable", JsonArray(downloadable))
    }

    /**
     * The answer built from what Pano stored at the node's last hello: for a node that is offline,
     * one too old to have a catalog, or one that did not answer in time.
     *
     * `usedBy` is empty because only the node can say which process runs from which home.
     */
    fun fallback(
        stored: NodeResources,
        online: Boolean,
        supported: Boolean,
        os: String?,
        arch: String?,
        catalogError: String? = null
    ): JsonObject = JsonObject()
        .put("online", online)
        .put("supported", supported)
        .put("os", os)
        .put("arch", arch)
        .put("libc", null)
        .put("autoDownload", stored.javaAutoDownload)
        .put("catalogError", catalogError)
        .put("runtimes", JsonArray(stored.javaRuntimes.map { runtimeJson(it, JsonArray()) }))
        .put("downloadable", JsonArray())

    private fun runtimeJson(runtime: JavaRuntimeData, usedBy: JsonArray): JsonObject =
        runtime.toJsonObject().put("usedBy", usedBy)

    /**
     * Server uuids to `{ id, name, uuid }`.
     *
     * A uuid Pano does not know on this node still comes through, with a null id and name: the
     * runtime is in use either way, and dropping it would make a Remove button look safe when the
     * node is about to refuse it.
     */
    private fun usedBy(uuids: JsonArray?, serversByUuid: Map<String, ServerRef>): JsonArray = JsonArray(
        (uuids ?: JsonArray())
            .asSequence()
            .mapNotNull { it as? String }
            .map { it.take(MAX_SHORT_FIELD) }
            .distinct()
            .take(MAX_USED_BY)
            .map { uuid ->
                val server = serversByUuid[uuid]

                JsonObject()
                    .put("id", server?.id)
                    .put("name", server?.name)
                    .put("uuid", uuid)
            }
            .toList()
    )

    private fun downloadableJson(json: JsonObject?): JsonObject? {
        if (json == null) {
            return null
        }

        val major = (json.getValue("major") as? Number)?.toInt()?.takeIf { it in MAJOR_RANGE } ?: return null

        return JsonObject()
            .put("major", major)
            .put("available", json.getValue("available") as? Boolean ?: false)
            .put("vendor", shortField(json.getValue("vendor")))
            .put("version", shortField(json.getValue("version")))
            .put("size", (json.getValue("size") as? Number)?.toLong()?.takeIf { it >= 0 })
            .put("installedVersion", shortField(json.getValue("installedVersion")))
            .put("updateAvailable", json.getValue("updateAvailable") as? Boolean ?: false)
    }

    private fun shortField(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SHORT_FIELD)

    private fun clampError(value: String?): String? =
        value?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_LENGTH)
}
