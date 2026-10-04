package com.panomc.platform.server.software.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * One software option in the create-server wizard.
 *
 * [versions] is releases first and then snapshots, each group newest first, which is the order the
 * wizard shows them in. [recommended] marks the option Pano nudges people towards (Paper), so the
 * picker can have a default that is not simply whatever sorts first, and [recommendedVersion] does
 * the same for the version — it is the one an install with no version picked actually uses, so a
 * wizard that pre-selects it is showing what is going to happen rather than guessing.
 */
data class SoftwareCatalogEntry(
    val id: String,
    val name: String,
    val recommended: Boolean,
    val versions: List<String>,
    /** The version Pano installs when none is chosen: the newest release, never a snapshot. */
    val recommendedVersion: String? = null,
    /** Why this option is discouraged, e.g. Waterfall being end of life. Null when it is fine. */
    val note: String? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("id", id)
        .put("name", name)
        .put("recommended", recommended)
        .put("recommendedVersion", recommendedVersion)
        .put("versions", JsonArray(versions))
        .put("note", note)
}
