package com.panomc.platform.node.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * What a node's host has to offer, as the node last reported it.
 *
 * Stored as a single JSON column instead of one column per number: this is a snapshot for display
 * and for the create-server wizard's sanity checks, nothing queries or aggregates it, and the shape
 * grows every time the node learns to measure something new.
 */
data class NodeResources(
    val cpuCores: Int = 0,
    val memTotal: Long = 0,
    val diskTotal: Long = 0,
    val javaRuntimes: List<JavaRuntimeData> = emptyList(),
    /**
     * Whether the node downloads a missing Java on its own (`java-auto-download` in its config,
     * SM-63). Null from a node too old to have the feature at all, which the panel shows
     * differently from "turned off".
     */
    val javaAutoDownload: Boolean? = null,
    /**
     * Whether the node understands `JAVA_CATALOG`, `JAVA_INSTALL` and `JAVA_REMOVE`.
     *
     * Decided once at hello time (see [com.panomc.platform.node.NodeJavaSupport]) and kept here
     * so the panel can hide the install buttons of an old node without asking it first, which
     * for an old node would mean sitting out a timeout: it silently ignores messages it does not
     * know.
     */
    val javaDownloads: Boolean = false
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("cpuCores", cpuCores)
        .put("memTotal", memTotal)
        .put("diskTotal", diskTotal)
        .put("javaRuntimes", JsonArray(javaRuntimes.map { it.toJsonObject() }))
        .put("javaAutoDownload", javaAutoDownload)
        .put("javaDownloads", javaDownloads)

    /**
     * The same snapshot with a new runtime list, from a `NODE_JAVA_RUNTIMES` frame. Everything
     * else is the hello's and stays as it was.
     */
    fun withJavaRuntimes(runtimes: List<JavaRuntimeData?>?): NodeResources =
        copy(javaRuntimes = JavaRuntimeData.sanitizeAll(runtimes))

    fun encode(): String = toJsonObject().encode()

    companion object {
        /**
         * Java runtimes kept per node. A host with more installed than this is either unusual or
         * lying, and the list is only ever shown in a picker.
         */
        const val MAX_JAVA_RUNTIMES = 32

        /** Empty resources, used until a node's first hello arrives. */
        val EMPTY = NodeResources()

        /**
         * Reads resources out of untrusted node JSON, clamping everything and dropping entries it
         * cannot use. Never throws: a malformed hello must cost the node its numbers, not its
         * connection.
         */
        fun fromJson(json: JsonObject?): NodeResources {
            if (json == null) {
                return EMPTY
            }

            val runtimes = (json.getJsonArray("javaRuntimes") ?: JsonArray())
                .take(MAX_JAVA_RUNTIMES)
                .mapNotNull { JavaRuntimeData.fromJson(it as? JsonObject) }

            return NodeResources(
                cpuCores = (json.getValue("cpuCores") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                memTotal = (json.getValue("memTotal") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                diskTotal = (json.getValue("diskTotal") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                javaRuntimes = runtimes,
                javaAutoDownload = json.getValue("javaAutoDownload") as? Boolean,
                javaDownloads = json.getValue("javaDownloads") as? Boolean ?: false
            )
        }

        /**
         * Builds resources from a node's decoded hello.
         *
         * The wire payload is decoded by a plain Gson with no adapters (see `NodeManager`), so the
         * clamping the JSON path does has to happen here too rather than being assumed.
         */
        fun fromReported(
            cpuCores: Int?,
            memTotal: Long?,
            diskTotal: Long?,
            javaRuntimes: List<JavaRuntimeData?>?,
            javaAutoDownload: Boolean? = null,
            javaDownloads: Boolean = false
        ): NodeResources = NodeResources(
            cpuCores = cpuCores?.coerceAtLeast(0) ?: 0,
            memTotal = memTotal?.coerceAtLeast(0) ?: 0,
            diskTotal = diskTotal?.coerceAtLeast(0) ?: 0,
            javaRuntimes = JavaRuntimeData.sanitizeAll(javaRuntimes),
            javaAutoDownload = javaAutoDownload,
            javaDownloads = javaDownloads
        )

        /** Parses the stored column text, falling back to [EMPTY] for anything unreadable. */
        fun decode(text: String?): NodeResources {
            if (text.isNullOrBlank()) {
                return EMPTY
            }

            return try {
                fromJson(JsonObject(text))
            } catch (_: Exception) {
                EMPTY
            }
        }
    }
}
