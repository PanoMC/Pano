package com.panomc.platform.server.plugins

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerPluginInstall
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.message.FileListMessage
import com.panomc.platform.node.message.InstallPluginMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.dto.ServerPluginData
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import com.panomc.platform.server.plugins.dto.ServerPluginFileData
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Installing, listing and removing the jar files in a managed server's plugin directory.
 *
 * The distinction this class exists to keep straight is between *files on disk* and *plugins the
 * game has loaded*. The running server only knows what it loaded at boot; the node only knows what
 * is in the directory now. A jar installed into a running server therefore appears in the file
 * list and not in the plugin list, and that difference is exactly what `restartRequired` reports
 * — inferring it from a timestamp or a reload would be guessing where the two lists already say
 * it plainly.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerPluginService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val fileClient: ManagedServerFileClient,
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val logger: Logger
) {
    /**
     * The jar each in-flight install will replace, by task uuid.
     *
     * In memory rather than in the row, because it is true only between the push and the task
     * ending: a Pano that restarts in the middle leaves a jar that is still installed and still
     * tracked, and the list's own cleanup removes it once the node says it is gone.
     */
    private val replacements = ConcurrentHashMap<String, String>()

    /**
     * Starts one plugin install and returns the task tracking it.
     *
     * The row exists before the push goes out, so a node that dies mid-download leaves a task an
     * admin can see failed rather than a spinner with nothing behind it.
     */
    suspend fun install(
        target: ManagedServerFileClient.Target,
        source: PluginSourceId,
        projectId: String,
        version: PluginVersionData,
        file: PluginVersionFileData,
        filename: String,
        replaceFilename: String?,
        createdBy: Long,
        sqlClient: SqlClient
    ): ServerTask {
        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = target.server.id,
            nodeId = target.nodeId,
            kind = ServerTaskKind.PLUGIN_INSTALL,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = "Installing $filename",
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task

        val sent = trySend(
            target,
            InstallPluginMessage(
                serverUuid = target.serverUuid,
                taskId = task.uuid,
                downloadUrl = file.url!!,
                filename = filename,
                targetDir = PluginLoaderMapping.targetDir(target.server.type),
                sha512 = file.sha512,
                sha1 = file.sha1,
                sha256 = file.sha256,
                replaceFilename = replaceFilename
            )
        )

        if (!sent) {
            throw NodeOffline()
        }

        // Written only once the push went out: a row for an install that never left Pano would
        // be a version claimed for a server nothing was ever sent about.
        track(target, source, projectId, version, filename, replaceFilename, task.uuid, createdBy, sqlClient)

        panelRealtimeHub.pushTaskProgress(stored)

        return stored
    }

    /**
     * Records where the jar this install is fetching came from.
     *
     * Best effort, and deliberately after the point of no return: the install is already happening
     * and a provenance row that would not store is worth a log line, not a failed install that
     * leaves a half-downloaded jar on a node.
     */
    private suspend fun track(
        target: ManagedServerFileClient.Target,
        source: PluginSourceId,
        projectId: String,
        version: PluginVersionData,
        filename: String,
        replaceFilename: String?,
        taskUuid: String,
        createdBy: Long,
        sqlClient: SqlClient
    ) {
        if (PluginFileNaming.isPanoPluginJar(filename)) {
            return
        }

        // Remembered rather than stored: the jar being replaced only matters until this one task
        // ends, and a restart in between is covered by the stale-row cleanup on the list.
        if (replaceFilename != null && replaceFilename != filename) {
            replacements[taskUuid] = replaceFilename
        }

        val project = try {
            pluginSourceCatalog.projectInfo(source, projectId)
        } catch (_: Exception) {
            null
        }

        try {
            databaseManager.serverPluginInstallDao.upsert(
                ServerPluginInstall(
                    serverId = target.server.id,
                    filename = filename,
                    source = source.id,
                    projectId = projectId,
                    projectName = project?.name,
                    pageUrl = project?.pageUrl,
                    versionId = version.id,
                    versionNumber = version.versionNumber ?: version.name,
                    publishedAt = version.publishedAt,
                    identified = false,
                    taskId = taskUuid,
                    createdBy = createdBy
                ),
                sqlClient
            )
        } catch (e: Exception) {
            logger.warn("Could not record where $filename came from: ${e.message}")
        }
    }

    /**
     * Resolves the provenance row a finished `PLUGIN_INSTALL` task owns.
     *
     * DONE turns the promise into a fact and removes the row of the jar this one replaced; FAILED
     * takes the promise back, because a download that never landed must not look like an installed
     * version the next update check would then offer to update.
     */
    suspend fun onInstallTaskFinished(
        taskUuid: String,
        serverId: Long?,
        status: ServerTaskStatus,
        sqlClient: SqlClient
    ) {
        val outcome = PluginInstallTracking.outcomeOf(ServerTaskKind.PLUGIN_INSTALL, status)

        val replaced = replacements.remove(taskUuid)

        try {
            when (outcome) {
                PluginInstallTracking.Outcome.CONFIRM -> {
                    databaseManager.serverPluginInstallDao.clearTaskId(taskUuid, sqlClient)

                    if (serverId != null && replaced != null) {
                        databaseManager.serverPluginInstallDao
                            .deleteByServerIdAndFilename(serverId, replaced, sqlClient)
                    }
                }

                PluginInstallTracking.Outcome.DISCARD ->
                    databaseManager.serverPluginInstallDao.deleteByTaskId(taskUuid, sqlClient)

                PluginInstallTracking.Outcome.IGNORE -> Unit
            }
        } catch (e: Exception) {
            logger.warn("Could not settle the plugin install row of task $taskUuid: ${e.message}")
        }
    }

    /**
     * The jar files currently in this server's plugin directory.
     *
     * Never fails the caller: a node that cannot list the directory (it does not exist yet on a
     * freshly installed server) means "no files", not a broken plugins page.
     */
    suspend fun listFiles(
        target: ManagedServerFileClient.Target,
        loadedPlugins: List<ServerPluginData>
    ): List<ServerPluginFileData> = listFilesOrNull(target, loadedPlugins).orEmpty()

    /**
     * [listFiles], but null when the directory could not be read at all.
     *
     * The difference matters to exactly one caller: the cleanup that forgets the provenance of
     * jars that are no longer there. "The node did not answer" and "the directory is empty" are
     * the same empty list and opposite facts, and treating the first as the second would erase
     * every tracked plugin on a server whose node blinked.
     */
    suspend fun listFilesOrNull(
        target: ManagedServerFileClient.Target,
        loadedPlugins: List<ServerPluginData>
    ): List<ServerPluginFileData>? {
        val directory = PluginLoaderMapping.targetDir(target.server.type)

        // Pano's own errors are Throwables rather than Exceptions, so they need their own catch: a
        // server without the directory yet makes the node answer NOT_FOUND, which arrives here as
        // `NotExists` and would otherwise turn the whole plugins page into a 404.
        val payload = try {
            fileClient.request(target, FileListMessage(target.serverUuid, directory))
        } catch (_: Exception) {
            return null
        } catch (_: com.panomc.platform.model.Error) {
            return null
        }

        val entries = payload.getJsonArray("entries") ?: return null

        return entries
            .mapNotNull { it as? JsonObject }
            .filter { it.getString("type") == "file" }
            // Switched-off jars too: a plugin disabled here has to stay on the page to be enabled again.
            .filter { PluginFileNaming.isPluginFileName(it.getString("name")) }
            .map { entry ->
                val name = entry.getString("name")
                val disabled = PluginFileNaming.isDisabledJarName(name)
                val jarName = PluginFileNaming.enabledNameOf(name)

                ServerPluginFileData(
                    filename = name,
                    size = entry.getLong("size", 0L) ?: 0L,
                    modified = entry.getLong("modified", 0L) ?: 0L,
                    // A switched-off jar only counts as a loaded plugin's file on an exact match
                    // when the server says which file each plugin came from: by prefix, the old
                    // version someone disabled would claim the plugin its replacement loaded.
                    matchedPlugin = if (disabled && loadedPlugins.any { it.file != null }) {
                        loadedPlugins.firstOrNull { it.file.equals(jarName, ignoreCase = true) }?.name
                    } else {
                        matchPlugin(jarName, loadedPlugins)
                    },
                    enabled = !disabled
                )
            }
    }

    /**
     * Which loaded plugin a jar most likely is.
     *
     * By filename prefix, and deliberately no cleverer than that: only the Bukkit family reports
     * the file a plugin came from, so for everything else the jar name is all there is, and
     * `EssentialsX-2.21.2.jar` matching the plugin `Essentials` is a guess a person can check
     * rather than an identity Pano should claim.
     */
    fun matchPlugin(filename: String, plugins: List<ServerPluginData>): String? {
        val exact = plugins.firstOrNull { it.file != null && it.file.equals(filename, ignoreCase = true) }

        if (exact != null) {
            return exact.name
        }

        val stem = filename.removeSuffix(".jar").removeSuffix(".JAR").lowercase()

        return plugins
            .filter { it.name.isNotBlank() }
            .filter { stem.startsWith(it.name.lowercase().replace(" ", "")) || stem.startsWith(it.name.lowercase()) }
            // The longest name that still prefixes the file is the most specific match, so
            // "WorldEdit" wins over "World" for WorldEdit-7.3.0.jar.
            .maxByOrNull { it.name.length }
            ?.name
    }

    /**
     * Whether the server has to be restarted for its plugin directory to match what is running.
     *
     * True when a switched-on jar exists that no loaded plugin accounts for (installed or enabled
     * since the start), or a switched-off one still belongs to a plugin that is loaded (disabled
     * since the start) with no switched-on jar of its own left. A jar that is off and not loaded
     * is exactly as it should be, and so is an old copy switched off beside the one that loaded.
     * An offline server is never "restart required": it reads the whole directory the moment it
     * starts.
     */
    fun restartRequired(files: List<ServerPluginFileData>, online: Boolean): Boolean =
        restartRequiredFor(files, online)

    /** Hands [message] to whichever side owns this server, reporting whether it went out. */
    private fun trySend(target: ManagedServerFileClient.Target, message: NodeMessage): Boolean = try {
        fileClient.send(target, message)

        true
    } catch (e: Exception) {
        logger.warn("Could not reach server ${target.server.id} to ${message.getResponseName()}: ${e.message}")

        false
    }

    companion object {
        /** [restartRequired] itself, pure so it can be tested without a node. */
        fun restartRequiredFor(files: List<ServerPluginFileData>, online: Boolean): Boolean {
            if (!online) {
                return false
            }

            val loadedFromEnabledJar = files
                .filter { it.enabled && it.matchedPlugin != null }
                .mapNotNull { it.matchedPlugin?.lowercase() }
                .toSet()

            return files.any { file ->
                val plugin = file.matchedPlugin

                if (file.enabled) {
                    plugin == null
                } else {
                    plugin != null && plugin.lowercase() !in loadedFromEnabledJar
                }
            }
        }
    }
}
