package com.panomc.platform.server.plugins

import com.panomc.platform.util.VersionUtil
import io.vertx.core.json.JsonObject

/**
 * The Pano plugin inside a server next to the one an install would put there now (SM-61, §2.4.26).
 *
 * [updateAvailable] is only an answer when both versions are real semantic versions: a development
 * jar reports `local-build`, a server with no plugin reports nothing, and a latest version that has
 * not been looked up yet is unknown — in every one of those cases the honest answer is null, and the
 * panel shows no badge rather than a wrong one.
 */
data class PanoPluginStatus(val version: String?, val latestVersion: String?) {
    val updateAvailable: Boolean?
        get() = updateAvailable(version, latestVersion)

    fun toJsonObject(): JsonObject = JsonObject()
        .put("version", version)
        .put("latestVersion", latestVersion)
        .put("updateAvailable", updateAvailable)

    companion object {
        /** Whether [latest] is newer than [installed], or null when the two cannot be compared. */
        fun updateAvailable(installed: String?, latest: String?): Boolean? {
            if (installed == null || latest == null) {
                return null
            }

            if (!VersionUtil.isSemVer(installed) || !VersionUtil.isSemVer(latest)) {
                return null
            }

            return VersionUtil.compareVersions(latest, installed) > 0
        }
    }
}
