package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.dto.PluginVersionData
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Whether there is a newer build of an installed plugin, decided on its own.
 *
 * Pure, and separate from everything that fetches, because this is the judgement the whole feature
 * rests on: a rule that is too eager offers somebody a pre-release of the plugin holding their
 * economy together, and one that is too shy silently never updates anything. Both failures are
 * invisible in a running system and obvious in a test, which is the argument for keeping the rule
 * where a test can reach it.
 *
 * Two decisions live here. Which versions count — a server running a stable build is only ever
 * offered stable builds, while a server the operator deliberately put on a beta keeps being
 * offered betas, because telling them "no updates" while their own channel moves on would be a
 * lie. And which of those is newest — by the source's own publication timestamp rather than by
 * version number, since "1.21.4-pre2" against "1.21.4" is a comparison no two plugin authors spell
 * the same way, while a publication date means one thing everywhere.
 */
object PluginUpdateRule {
    /** The channel every plugin is offered regardless of what it is on today. */
    const val STABLE_CHANNEL = "release"

    /**
     * The versions worth offering to a server running [installedChannel].
     *
     * A version with no channel counts as stable: Hangar names a channel per project and an author
     * is free to call it anything, so an unrecognised or absent channel must not mean "never
     * update this plugin again".
     */
    fun candidates(versions: List<PluginVersionData>, installedChannel: String?): List<PluginVersionData> {
        val installed = installedChannel?.lowercase()?.takeIf { it.isNotBlank() }

        return versions.filter { version ->
            if (!version.compatible) {
                return@filter false
            }

            val channel = version.channel?.lowercase()?.takeIf { it.isNotBlank() } ?: return@filter true

            channel == STABLE_CHANNEL || channel == installed
        }
    }

    /**
     * The newest candidate, or null when there is none.
     *
     * Falls back to the source's own order when nothing carries a usable timestamp, because every
     * source here already answers newest-first and that order is a better guess than the arbitrary
     * one a stable sort would leave behind.
     */
    fun latest(versions: List<PluginVersionData>, installedChannel: String?): PluginVersionData? {
        val candidates = candidates(versions, installedChannel)

        if (candidates.isEmpty()) {
            return null
        }

        val dated = candidates.mapNotNull { version -> timestampOf(version.publishedAt)?.let { version to it } }

        if (dated.isEmpty()) {
            return candidates.first()
        }

        return dated.maxByOrNull { it.second }?.first
    }

    /**
     * Whether [latest] is something the installed build does not already have.
     *
     * Both halves are needed. A different version id alone would offer an "update" to an older
     * build whenever a source reorders its list or an author pulls a release; a newer timestamp
     * alone would offer the same build again whenever a source re-publishes it. An installed
     * version with no recorded timestamp is the one case where the id has to answer on its own —
     * that is a row from before Pano recorded dates, and refusing to ever update it would be
     * worse than trusting the source's idea of newest.
     */
    fun isUpdateAvailable(
        installedVersionId: String?,
        installedPublishedAt: String?,
        latest: PluginVersionData?
    ): Boolean {
        val candidate = latest ?: return false

        if (installedVersionId != null && candidate.id == installedVersionId) {
            return false
        }

        val installedAt = timestampOf(installedPublishedAt) ?: return true
        val candidateAt = timestampOf(candidate.publishedAt) ?: return false

        return candidateAt.isAfter(installedAt)
    }

    /**
     * One source's timestamp as an instant, or null when it is not one.
     *
     * Three sources, three spellings: Modrinth and CurseForge answer ISO-8601 with an offset,
     * Hangar sometimes without one. Anything that cannot be read is null rather than an exception,
     * because a timestamp Pano cannot parse is a reason to fall back on order, not to fail an
     * update check.
     */
    fun timestampOf(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: Exception) {
            try {
                Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(text))
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME)
                        .toInstant(java.time.ZoneOffset.UTC)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
