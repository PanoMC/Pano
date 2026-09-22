package com.panomc.platform.server.plugins

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerPluginInstall
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.message.FileHashesMessage
import com.panomc.platform.server.plugins.dto.IdentifiedPluginData
import com.panomc.platform.server.plugins.dto.PluginHashMatch
import com.panomc.platform.server.plugins.dto.PluginProjectInfo
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Working out what a jar somebody uploaded by hand actually is.
 *
 * Pano knows the provenance of everything it installed, and nothing about the rest — which on a
 * real server is most of the directory, because plugins arrive over SFTP and through file managers
 * far more often than through a panel. Both sites that can answer "which project is this file"
 * answer it by hash, so this asks the node for the hashes and asks them.
 *
 * Two hashes for two sites and no more. Modrinth looks files up by SHA-1; CurseForge only by its
 * own murmur2 fingerprint, and only when an operator has configured a key. Hangar publishes no
 * hash index at all, so a Hangar-only plugin uploaded by hand stays unknown — that is a property
 * of Hangar and not something a better implementation here would fix.
 *
 * Nothing in here throws at its caller. It runs on the plugins page, and a source having a bad
 * minute must cost the identification, never the page.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PluginIdentificationService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val fileClient: ManagedServerFileClient,
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val logger: Logger
) {
    /** One jar's hashes as the node reported them. */
    data class FileHashes(
        val filename: String,
        val sha1: String?,
        val sha512: String?,
        val murmur2: String?
    )

    /** What one identification run found, and what it could not place. */
    data class Result(val identified: List<IdentifiedPluginData>, val unknown: List<String>)

    /** When each server was last identified automatically, so opening the page is not a request storm. */
    private val lastAutomaticRun = ConcurrentHashMap<Long, Long>()

    /**
     * Whether [nodeId] can answer a hash request.
     *
     * The daemon learned `FILE_HASHES` in protocol 2, and an older one answers `UNKNOWN_OPERATION`
     * after a full round trip. The panel is told up front rather than after a failed press.
     */
    fun isSupported(nodeId: Long?): Boolean {
        val id = nodeId ?: return false

        val node = nodeManager.getConnectedNodeById(id) ?: return false

        return node.protocolVersion >= NodeProtocol.FILE_HASHES_VERSION
    }

    /**
     * [isSupported] for a resolved target, which may be answered by the plugin (§2.4.17 C).
     *
     * A plugin channel only exists because the resolver already found a plugin announcing
     * `plugin-install`, and `FILE_HASHES` is part of that capability, so there is no version to
     * check on that side -- announcing it is the version check.
     */
    fun isSupported(target: ManagedServerFileClient.Target): Boolean =
        target.nodeId?.let { isSupported(it) } ?: true

    /**
     * Identifies whichever of [filenames] Pano has no record of, and records what it finds.
     *
     * Only jars without a row, which is both cheaper and safer. A jar Pano installed already has
     * exact provenance — the project and version Pano itself asked for — and a hash match would
     * replace that with a *guess-shaped fact* derived from bytes, losing the installer and the
     * distinction between "we installed this" and "we recognised this".
     *
     * The Pano plugin jar is never among them either: it did not come from a plugin site, so
     * asking about it can only produce a wrong answer, and an "update available" on the jar that
     * carries the panel's own connection would be actively dangerous.
     */
    suspend fun identify(
        target: ManagedServerFileClient.Target,
        filenames: List<String>,
        sqlClient: SqlClient
    ): Result {
        val known = try {
            databaseManager.serverPluginInstallDao
                .getByServerId(target.server.id, sqlClient)
                .map { it.filename }
                .toSet()
        } catch (e: Exception) {
            logger.warn("Could not read the tracked plugins of server ${target.server.id}: ${e.message}")

            emptySet()
        }

        val candidates = filenames
            .filter { PluginFileNaming.isJarName(it) && !PluginFileNaming.isPanoPluginJar(it) }
            .filterNot { it in known }
            .distinct()
            .take(MAX_FILES)

        if (candidates.isEmpty() || !isSupported(target)) {
            return Result(emptyList(), candidates)
        }

        val hashes = try {
            hashes(target, candidates)
        } catch (e: Exception) {
            logger.warn("Could not hash the plugin directory of server ${target.server.id}: ${e.message}")

            return Result(emptyList(), candidates)
        }

        if (hashes.isEmpty()) {
            return Result(emptyList(), candidates)
        }

        val found = mutableListOf<IdentifiedPluginData>()

        val remaining = hashes.toMutableList()

        modrinth(remaining).forEach { (file, data) ->
            found.add(data)
            remaining.removeIf { it.filename == file }
        }

        curseForge(remaining).forEach { (file, data) ->
            found.add(data)
            remaining.removeIf { it.filename == file }
        }

        record(target.server.id, found, sqlClient)

        val identifiedNames = found.map { it.filename }.toSet()

        return Result(found, candidates.filter { it !in identifiedNames })
    }

    /**
     * Identifies what has not been identified for a while, at most once per server per six hours.
     *
     * Called when somebody opens the plugins page, which is exactly when the answer is wanted and
     * exactly the moment it must not cost a visible wait for a request Pano makes every time. The
     * gate is in memory on purpose: after a restart it is worth asking again, because the
     * directory may have changed while Pano was not running.
     */
    suspend fun identifyIfDue(
        target: ManagedServerFileClient.Target,
        filenames: List<String>,
        sqlClient: SqlClient
    ): Boolean {
        if (filenames.isEmpty() || !isSupported(target)) {
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastAutomaticRun[target.server.id]

        if (last != null && now - last < AUTOMATIC_INTERVAL_MS) {
            return false
        }

        lastAutomaticRun[target.server.id] = now

        return try {
            identify(target, filenames, sqlClient).identified.isNotEmpty()
        } catch (e: Exception) {
            logger.warn("Automatic plugin identification for server ${target.server.id} failed: ${e.message}")

            false
        } catch (e: com.panomc.platform.model.Error) {
            // A refusal from the side that serves the files (Pano's errors are not Exceptions) is
            // the same "not this time" — the page it runs under must still load.
            logger.warn("Automatic plugin identification for server ${target.server.id} failed: ${e.getErrorCode()}")

            false
        }
    }

    /** Forgets a server that no longer exists. */
    fun onServerDeleted(serverId: Long) {
        lastAutomaticRun.remove(serverId)
    }

    /** Asks the node for the hashes of [filenames] in this server's plugin directory. */
    suspend fun hashes(target: ManagedServerFileClient.Target, filenames: List<String>): List<FileHashes> {
        val directory = PluginLoaderMapping.targetDir(target.server.type)

        val payload = fileClient.request(
            target,
            FileHashesMessage(target.serverUuid, directory, filenames.take(MAX_FILES)),
            HASH_REQUEST_TIMEOUT_MS
        )

        val entries = payload.getJsonArray("files") ?: return emptyList()

        return entries
            .mapNotNull { it as? JsonObject }
            .mapNotNull { entry ->
                val name = entry.getString("name") ?: return@mapNotNull null

                FileHashes(
                    filename = name,
                    sha1 = entry.getString("sha1"),
                    sha512 = entry.getString("sha512"),
                    murmur2 = entry.getString("murmur2")
                )
            }
    }

    private suspend fun modrinth(files: List<FileHashes>): List<Pair<String, IdentifiedPluginData>> {
        val byHash = files
            .mapNotNull { file -> file.sha1?.lowercase()?.let { it to file.filename } }
            .toMap()

        if (byHash.isEmpty()) {
            return emptyList()
        }

        val matches = pluginSourceCatalog.identifyByModrinthSha1(byHash.keys.toList())

        if (matches.isEmpty()) {
            return emptyList()
        }

        val projects = pluginSourceCatalog
            .modrinthProjects(matches.map { it.projectId })
            .associateBy { it.projectId }

        return matches.mapNotNull { match ->
            val filename = byHash[match.key.lowercase()] ?: return@mapNotNull null

            filename to toData(filename, PluginSourceId.MODRINTH, match, projects[match.projectId])
        }
    }

    private suspend fun curseForge(files: List<FileHashes>): List<Pair<String, IdentifiedPluginData>> {
        if (!pluginSourceCatalog.isCurseForgeConfigured()) {
            return emptyList()
        }

        val byFingerprint = files
            .mapNotNull { file -> file.murmur2?.let { it to file.filename } }
            .toMap()

        if (byFingerprint.isEmpty()) {
            return emptyList()
        }

        val matches = pluginSourceCatalog.identifyByCurseForgeFingerprint(byFingerprint.keys.toList())

        if (matches.isEmpty()) {
            return emptyList()
        }

        val projects = pluginSourceCatalog
            .curseForgeMods(matches.map { it.projectId })
            .associateBy { it.projectId }

        return matches.mapNotNull { match ->
            val filename = byFingerprint[match.key] ?: return@mapNotNull null

            filename to toData(filename, PluginSourceId.CURSEFORGE, match, projects[match.projectId])
        }
    }

    private fun toData(
        filename: String,
        source: PluginSourceId,
        match: PluginHashMatch,
        project: PluginProjectInfo?
    ) = IdentifiedPluginData(
        filename = filename,
        source = source.id,
        projectId = match.projectId,
        projectName = project?.name,
        pageUrl = project?.pageUrl,
        versionId = match.versionId,
        versionNumber = match.versionNumber,
        publishedAt = match.publishedAt
    )

    /**
     * Writes down what was identified.
     *
     * Best effort per row: one row that will not store must not lose the other nineteen, and none
     * of it may reach the caller, which is a page load.
     */
    private suspend fun record(serverId: Long, found: List<IdentifiedPluginData>, sqlClient: SqlClient) {
        found.forEach { data ->
            try {
                databaseManager.serverPluginInstallDao.upsert(
                    ServerPluginInstall(
                        serverId = serverId,
                        filename = data.filename,
                        source = data.source,
                        projectId = data.projectId,
                        projectName = data.projectName,
                        pageUrl = data.pageUrl,
                        versionId = data.versionId,
                        versionNumber = data.versionNumber,
                        publishedAt = data.publishedAt,
                        identified = true,
                        taskId = null
                    ),
                    sqlClient
                )
            } catch (e: Exception) {
                logger.warn("Could not record the identified plugin ${data.filename}: ${e.message}")
            }
        }
    }

    companion object {
        /** Matches the node's own ceiling on one `FILE_HASHES` request. */
        const val MAX_FILES = 200

        /** Hashing reads whole jars off a possibly slow disk, so it gets longer than a listing. */
        const val HASH_REQUEST_TIMEOUT_MS = 120_000L

        /** How long an automatic identification stands before the next page view redoes it. */
        const val AUTOMATIC_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
