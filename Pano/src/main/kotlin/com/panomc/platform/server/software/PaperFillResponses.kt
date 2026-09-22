package com.panomc.platform.server.software

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** The build the catalog picked out of a fill v3 builds response. */
data class PaperBuild(
    val build: Int,
    val downloadUrl: String,
    val fileName: String?,
    val sha256: String?,
    val channel: String?
)

/**
 * Reads the two PaperMC "fill" v3 responses the catalog needs.
 *
 * Kept apart from [ServerSoftwareCatalog] so the mapping can be tested against a captured
 * response instead of the live API -- which is exactly the thing that broke: v2 was retired, the
 * shapes changed underneath, and every Paper-family software quietly listed zero versions because
 * nothing here ever asserted on a real body.
 */
object PaperFillResponses {
    /**
     * Every version the project offers, newest first.
     *
     * v3 answers with `versions` as an object of version-group to versions, e.g.
     * `{"1.21": ["1.21.11", ...], "1.20": [...]}`, each group's list newest first. The groups are
     * sorted here rather than trusted to arrive in order, because a map's order is the one thing
     * JSON promises nothing about, and Minecraft's 2026 move to `26.x` means "sorts after 1.21"
     * has to be a numeric comparison rather than a string one.
     */
    fun versions(project: JsonObject?): List<String> {
        val groups = project?.getJsonObject("versions") ?: return emptyList()

        return groups.fieldNames()
            .sortedWith(SoftwareVersions.NEWEST_FIRST)
            .flatMap { group ->
                (groups.getJsonArray(group) ?: JsonArray()).mapNotNull { it as? String }
            }
    }

    /**
     * The build to install for one version, or null when the response holds none usable.
     *
     * The array is newest first. A stable build is preferred over whatever is newest, so a
     * version whose latest build is experimental installs the last one PaperMC actually blessed;
     * when the project publishes no channel at all (or only experimental ones) the newest is used,
     * because "no install available" would be a worse answer than "the only build there is".
     */
    fun latestBuild(builds: JsonArray?): PaperBuild? {
        val entries = (builds ?: return null).mapNotNull { it as? JsonObject }

        if (entries.isEmpty()) {
            return null
        }

        val preferred = entries.firstOrNull { it.getString("channel")?.uppercase() in STABLE_CHANNELS }

        return toBuild(preferred) ?: entries.firstNotNullOfOrNull { toBuild(it) }
    }

    private fun toBuild(entry: JsonObject?): PaperBuild? {
        val build = entry?.getInteger("id") ?: return null

        // "server:default" is fill's name for the plain server jar; the same response also carries
        // things like "server:mojmap", which is not what a server is started from.
        val download = entry.getJsonObject("downloads")?.getJsonObject(SERVER_DOWNLOAD) ?: return null

        val url = download.getString("url") ?: return null

        if (!ServerSoftwareUrls.isPaperDownload(url)) {
            return null
        }

        return PaperBuild(
            build = build,
            downloadUrl = url,
            fileName = download.getString("name"),
            sha256 = download.getJsonObject("checksums")?.getString("sha256"),
            channel = entry.getString("channel")
        )
    }

    const val SERVER_DOWNLOAD = "server:default"

    private val STABLE_CHANNELS = setOf("STABLE", "RECOMMENDED")
}
