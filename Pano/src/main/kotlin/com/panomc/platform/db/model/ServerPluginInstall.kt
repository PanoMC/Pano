package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import io.vertx.core.json.JsonObject

/**
 * Where one jar in a managed server's plugin directory came from.
 *
 * A plugin directory is a pile of files with no provenance: `EssentialsX-2.21.2.jar` says what it
 * is only to somebody who already knows, and nothing on disk says which project, which version or
 * which site it was downloaded from. This table is that memory, and it is what makes "is there a
 * newer build of this" answerable at all.
 *
 * Two ways a row appears, told apart by [identified]. Pano installed it, in which case the source
 * and version are what Pano asked for and are exact; or Pano recognised a hand-uploaded jar by its
 * hash, in which case the match is a source's own answer about the bytes on disk and is exact for
 * a different reason. Neither is a guess, which is why a jar nothing matched stays untracked
 * rather than being recorded as a maybe.
 *
 * [taskId] is set only while an install is still running. A row with one is a promise rather than
 * a fact: the file is not on disk yet, so it is exempt from the cleanup that removes rows whose
 * jar has disappeared, and the install's DONE or FAILED is what decides which of the two it turns
 * out to be.
 */
data class ServerPluginInstall(
    val id: Long = -1,
    val serverId: Long,
    /** A single path segment inside the server's plugin directory; unique per server. */
    val filename: String,
    /** [com.panomc.platform.server.plugins.PluginSourceId.id]. */
    var source: String,
    var projectId: String,
    var projectName: String? = null,
    var pageUrl: String? = null,
    var versionId: String? = null,
    var versionNumber: String? = null,
    /** The source's own publication timestamp, kept verbatim because only it compares. */
    var publishedAt: String? = null,
    /** True when this row came from a hash match rather than from an install Pano performed. */
    var identified: Boolean = false,
    /** The unfinished `PLUGIN_INSTALL` task, or null once there is a file behind this row. */
    var taskId: String? = null,
    val createdBy: Long? = null,
    var installedAt: Long = System.currentTimeMillis()
) : DBEntity() {
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this)

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerPluginInstall && other.id == this.id
}
