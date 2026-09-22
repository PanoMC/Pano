package com.panomc.platform.server.plugins

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerPluginInstall
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import com.panomc.platform.server.plugins.dto.TrackedPluginData
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Whether anything a server has installed has a newer build waiting.
 *
 * Reading, not acting: this answers the question and the endpoints decide what to do with the
 * answer, because the same answer drives a badge on a page, a button, and a nightly e-mail, and
 * only one of those is allowed to be slow.
 *
 * Which is the thing this class is mostly about. Every row is a lookup at a third-party API, so a
 * server with thirty plugins is thirty requests, and a plugins page that made all of them before
 * rendering would take a minute the first time and get Pano rate limited the second. Two defences:
 * [PluginSourceCatalog] already caches a project's versions for ten minutes, and a caller can hand
 * over a deadline — rows that do not fit in it come back without an answer instead of holding the
 * page, and the next visit finds the earlier ones cached and gets further. It converges, and it
 * never stampedes.
 *
 * Nothing here throws. An update check is an extra on every screen it appears on.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PluginUpdateService(
    private val databaseManager: DatabaseManager,
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val logger: Logger
) {
    /** One tracked row resolved against its source. */
    data class Resolution(
        val row: ServerPluginInstall,
        val latest: PluginVersionData?,
        val updateAvailable: Boolean
    )

    /**
     * Every tracked jar of [server], with whatever is known about a newer build.
     *
     * [deadlineAt] is a wall-clock moment after which no further source lookups are made; null
     * means take as long as it takes, which is what the nightly sweep wants and no request ever
     * does.
     */
    suspend fun tracked(
        server: Server,
        rows: List<ServerPluginInstall>,
        deadlineAt: Long? = null
    ): List<TrackedPluginData> = resolve(server, rows, deadlineAt).map { resolution ->
        val row = resolution.row
        val latest = resolution.latest

        TrackedPluginData(
            filename = row.filename,
            source = row.source,
            projectId = row.projectId,
            projectName = row.projectName,
            pageUrl = row.pageUrl,
            identified = row.identified,
            versionId = row.versionId,
            versionNumber = row.versionNumber,
            updateAvailable = resolution.updateAvailable,
            latestVersionId = latest?.id,
            latestVersionNumber = latest?.versionNumber ?: latest?.name,
            latestPublishedAt = latest?.publishedAt
        )
    }

    /** [tracked], but keeping the version objects, for the endpoints that have to install one. */
    suspend fun resolve(
        server: Server,
        rows: List<ServerPluginInstall>,
        deadlineAt: Long? = null
    ): List<Resolution> {
        val resolutions = mutableListOf<Resolution>()

        // Sequential on purpose. Thirty parallel requests to one API is the definition of the
        // behaviour these sources ban applications for, and nobody is waiting on the difference.
        rows.forEach { row ->
            val expired = deadlineAt != null && System.currentTimeMillis() >= deadlineAt

            // A row an install still owns has no file behind it yet, so there is nothing to
            // update and no reason to spend a request finding that out.
            if (expired || row.taskId != null) {
                resolutions.add(Resolution(row, null, false))

                return@forEach
            }

            resolutions.add(resolveOne(server, row))
        }

        return resolutions
    }

    /** One row against its source, or an unanswered row when the source cannot say. */
    suspend fun resolveOne(server: Server, row: ServerPluginInstall): Resolution {
        val source = PluginSourceId.fromId(row.source) ?: return Resolution(row, null, false)

        val versions = try {
            pluginSourceCatalog.versions(
                source = source,
                type = server.type,
                softwareVersion = server.softwareVersion ?: server.version,
                projectId = row.projectId
            )
        } catch (e: Exception) {
            logger.warn("Checking ${row.filename} on ${source.id} for updates failed: ${e.message}")

            emptyList()
        }

        if (versions.isEmpty()) {
            return Resolution(row, null, false)
        }

        // The channel comes from the installed version itself rather than from a column: a server
        // deliberately put on a beta keeps being offered betas, and a version the author has since
        // pulled simply falls back to stable, which is the conservative half of the rule.
        val installedChannel = versions.firstOrNull { it.id == row.versionId }?.channel

        val latest = PluginUpdateRule.latest(versions, installedChannel)

        return Resolution(
            row = row,
            latest = latest,
            updateAvailable = PluginUpdateRule.isUpdateAvailable(row.versionId, row.publishedAt, latest)
        )
    }

    /** Everything an update needs, once it is known there is one. */
    data class UpdatePlan(
        val row: ServerPluginInstall,
        val source: PluginSourceId,
        val version: PluginVersionData,
        val file: PluginVersionFileData,
        val filename: String
    )

    /** Either an update that can be started, or why it cannot be. */
    sealed interface UpdateAttempt {
        data class Ready(val plan: UpdatePlan) : UpdateAttempt

        data class Refused(val reason: String) : UpdateAttempt
    }

    /**
     * Works out what updating one tracked jar would mean.
     *
     * A single answer for both endpoints, because "update this one" and "update all of them" must
     * refuse the same things for the same reasons: one of them turns a refusal into an HTTP error
     * and the other into a line in a skipped list, and that is the only difference between them.
     */
    suspend fun plan(server: Server, row: ServerPluginInstall): UpdateAttempt {
        // The jar the panel is talking over. Never updated from a plugin site, because it does not
        // come from one.
        if (PluginFileNaming.isPanoPluginJar(row.filename)) {
            return UpdateAttempt.Refused(REFUSED_PANO_PLUGIN)
        }

        val source = PluginSourceId.fromId(row.source) ?: return UpdateAttempt.Refused(REFUSED_UNKNOWN_SOURCE)

        val resolution = resolveOne(server, row)

        if (!resolution.updateAvailable || resolution.latest == null) {
            return UpdateAttempt.Refused(REFUSED_UP_TO_DATE)
        }

        val version = resolution.latest

        // The primary file first, exactly as an install from the panel would pick it: a version
        // often ships a sources jar next to the one that runs.
        val file = version.files.firstOrNull { !it.external && !it.url.isNullOrBlank() }
            ?: return UpdateAttempt.Refused(
                if (version.files.isEmpty()) REFUSED_NO_FILE else REFUSED_EXTERNAL
            )

        return UpdateAttempt.Ready(
            UpdatePlan(
                row = row,
                source = source,
                version = version,
                file = file,
                filename = PluginFileNaming.sanitise(file.filename, fallback = version.id)
            )
        )
    }

    /**
     * Forgets the rows whose jar is no longer in the directory.
     *
     * A plugin removed through the file manager, renamed by hand or replaced by an upload leaves
     * a row that would otherwise keep claiming a version nothing on disk has. Done lazily, here,
     * because there is no event for "somebody deleted a file over SFTP" and the list is the moment
     * Pano finds out.
     *
     * [presentFilenames] must be null when the directory could not be read: an empty set means an
     * empty directory, and mistaking one for the other erases everything the server knew.
     */
    suspend fun forgetMissing(
        serverId: Long,
        rows: List<ServerPluginInstall>,
        presentFilenames: Set<String>?,
        sqlClient: SqlClient
    ): List<ServerPluginInstall> {
        if (presentFilenames == null) {
            return rows
        }

        val (stale, kept) = rows.partition {
            PluginInstallTracking.isStale(it.filename, it.taskId, presentFilenames)
        }

        stale.forEach { row ->
            try {
                databaseManager.serverPluginInstallDao.deleteByServerIdAndFilename(serverId, row.filename, sqlClient)
            } catch (e: Exception) {
                logger.warn("Could not forget the removed plugin ${row.filename}: ${e.message}")
            }
        }

        return kept
    }

    companion object {
        /**
         * How long a page load may spend asking sources before it answers with what it has.
         *
         * Short enough that a server with a long plugin list still renders, long enough that a
         * handful of uncached projects resolve on the first visit rather than the third.
         */
        const val LIST_BUDGET_MS = 6_000L

        /** Nothing newer than what is installed. */
        const val REFUSED_UP_TO_DATE = "UP_TO_DATE"

        /** The row names a source this build does not have, which only a downgrade can produce. */
        const val REFUSED_UNKNOWN_SOURCE = "UNKNOWN_SOURCE"

        /** The newest version publishes nothing downloadable. */
        const val REFUSED_NO_FILE = "NO_FILE"

        /** The newest version only links out to the author's own page, which is not a jar. */
        const val REFUSED_EXTERNAL = "EXTERNAL_DOWNLOAD"

        /** The Pano plugin, which is not updated from a plugin site. */
        const val REFUSED_PANO_PLUGIN = "PANO_PLUGIN"
    }
}
