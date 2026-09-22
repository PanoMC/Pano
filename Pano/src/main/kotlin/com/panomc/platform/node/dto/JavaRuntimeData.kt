package com.panomc.platform.node.dto

import io.vertx.core.json.JsonObject

/**
 * One Java runtime a node found (or downloaded) on its host.
 *
 * The node reports these on every hello so Pano can tell the admin which Minecraft versions that
 * host can actually run, and so the create-server wizard can pre-select a Java major instead of
 * letting the install fail minutes later. Since SM-63 (§2.4.28) the same list also arrives on its
 * own as `NODE_JAVA_RUNTIMES` whenever the node installs or removes a runtime.
 */
data class JavaRuntimeData(
    val major: Int = 0,
    val path: String = "",
    val vendor: String? = null,
    /**
     * The full version (`JAVA_VERSION` out of the runtime's `release` file, e.g. `21.0.12`), or
     * null when the node could not read one or is too old to report it.
     */
    val version: String? = null,
    /**
     * Whether the node downloaded this runtime itself (it lives under `<data>/java/` with a
     * `.pano-managed.json`). Only managed runtimes can be updated or removed from the panel; a
     * node too old to say is read as "not managed", which is the answer that offers nothing
     * destructive.
     */
    val managed: Boolean = false
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("major", major)
        .put("path", path)
        .put("vendor", vendor)
        .put("version", version)
        .put("managed", managed)

    /** The same runtime with every string clamped, for data that came from a node. */
    // The casts are not useless: the wire is decoded by a plain Gson, which writes an explicit
    // JSON null straight into a non-null Kotlin field.
    @Suppress("USELESS_CAST")
    fun sanitized(): JavaRuntimeData = copy(
        path = (path as String?).orEmpty().take(MAX_PATH_LENGTH),
        vendor = vendor?.take(MAX_VENDOR_LENGTH),
        version = version?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_VERSION_LENGTH)
    )

    companion object {
        /** Longest path accepted from a node, so one bad hello cannot bloat the stored JSON. */
        const val MAX_PATH_LENGTH = 512

        const val MAX_VENDOR_LENGTH = 128

        /** Longest full version kept. Real ones are around 15 characters (`21.0.12+7-LTS`). */
        const val MAX_VERSION_LENGTH = 64

        /**
         * Reads one runtime out of untrusted node JSON. Returns `null` when the entry carries no
         * usable major version, because an entry Pano cannot key on is worse than no entry.
         */
        fun fromJson(json: JsonObject?): JavaRuntimeData? {
            if (json == null) {
                return null
            }

            val major = (json.getValue("major") as? Number)?.toInt() ?: return null

            if (major <= 0) {
                return null
            }

            return JavaRuntimeData(
                major = major,
                path = json.getValue("path") as? String ?: "",
                vendor = json.getValue("vendor") as? String,
                version = json.getValue("version") as? String,
                managed = json.getValue("managed") as? Boolean ?: false
            ).sanitized()
        }

        /**
         * Cleans a list the node sent, however it was decoded: entries without a usable major are
         * dropped and the list is capped at [NodeResources.MAX_JAVA_RUNTIMES].
         */
        fun sanitizeAll(runtimes: List<JavaRuntimeData?>?): List<JavaRuntimeData> = (runtimes ?: emptyList())
            .filterNotNull()
            .take(NodeResources.MAX_JAVA_RUNTIMES)
            .filter { it.major > 0 }
            .map { it.sanitized() }
    }
}
