package com.panomc.node.task

import com.panomc.node.console.ConsoleLevel
import com.panomc.node.net.InstallPanoPluginMessage
import com.panomc.node.net.InstallPluginMessage
import com.panomc.node.net.PlatformUrls
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.Downloader
import com.panomc.node.util.FileHash
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Downloads one plugin or mod jar into a server and leaves nothing half-written behind.
 *
 * Everything here exists because the file being installed comes from a third party over a URL
 * Pano only forwarded. The download lands on a `.part` name so a server that starts mid-download
 * never sees a truncated jar; the checksum the source published is verified here rather than in
 * Pano, which never touches the bytes; the zip magic is checked because a CDN outage answers with
 * an HTML error page that would otherwise be saved under a `.jar` name and crash the server on
 * boot; and only then is it moved into place, atomically where the filesystem allows it.
 *
 * [InstallPluginMessage.replaceFilename] is deleted last, after the replacement is in place: an
 * update that fails halfway should leave the old jar working, not a server with no plugin at all.
 */
class PluginInstallService(
    private val registry: ServerRegistry,
    private val reporter: TaskSink,
    private val panoPluginInstaller: PanoPluginInstaller,
    private val platformUrls: PlatformUrls,
    private val logger: NodeLogger,
    /**
     * Starts a server whose Pano plugin install carried its start: the daemon's auto-start, under
     * the auto-start's issuer. A parameter so a test can see the start without a JVM behind it.
     */
    private val startServer: (ServerProcess) -> Unit
) {
    /**
     * Puts the Pano plugin into a server that already exists (`INSTALL_PANO_PLUGIN`).
     *
     * The same installer a fresh install uses, driven from a message of its own because an import
     * only learns which plugin it needs after the directory has been inspected. Reported as a
     * [KIND] task, so a server imported without a linkable plugin shows a failed step rather than
     * silently coming up unlinked.
     *
     * With [startAfter] the server is started once the install has ended, done or failed alike:
     * either way the plugin question is settled, and a server that wants to run should not stay
     * down because a download did not work. By default that is what Pano asked for
     * ([InstallPanoPluginMessage.startAfter], set on the install that follows an import in place
     * of the START that used to race it); the daemon also passes true when this install is what an
     * agent's first start was waiting for. A server that is already up is left alone.
     */
    fun installPanoPlugin(message: InstallPanoPluginMessage, startAfter: Boolean = message.startAfter == true) {
        try {
            linkPanoPlugin(message)
        } finally {
            if (startAfter) {
                startAfterInstall(message.serverUuid)
            }
        }
    }

    /**
     * Starts [uuid]'s server for an install that carried its start, unless it is already up.
     *
     * [ServerProcess.start] ignores a start of a live server as well; checked here too so that a
     * server somebody started by hand meanwhile does not have a second start logged against it.
     */
    private fun startAfterInstall(uuid: String?) {
        val server = registry.get(uuid) ?: return

        if (server.state.isAlive) {
            return
        }

        try {
            startServer(server)
        } catch (exception: Exception) {
            logger.warn("Could not start $uuid after installing the Pano plugin: ${exception.message}")
        }
    }

    private fun linkPanoPlugin(message: InstallPanoPluginMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank()) {
            logger.warn("Ignoring an INSTALL_PANO_PLUGIN with no server uuid or task id.")

            return
        }

        val server = registry.get(uuid)

        if (server == null) {
            reporter.failed(taskId, uuid, KIND, "This node does not hold that server.")

            return
        }

        try {
            reporter.running(taskId, uuid, KIND, LINK_PERCENT, "Installing the Pano plugin")

            val jar = when (val outcome = panoPluginInstaller.install(server.directory, message.spec)) {
                is PanoPluginInstaller.Outcome.Installed -> outcome.jar

                is PanoPluginInstaller.Outcome.Failed ->
                    throw IllegalStateException("The Pano plugin could not be installed: ${outcome.error}")

                PanoPluginInstaller.Outcome.None ->
                    throw IllegalStateException("The Pano plugin could not be installed into this server.")
            }

            server.emit(ConsoleLevel.INFO, "Pano installed $jar; this server is already linked.")

            reporter.done(taskId, uuid, KIND, "Installed the Pano plugin")
        } catch (exception: Exception) {
            logger.error("Pano plugin install on $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, KIND, exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun install(message: InstallPluginMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank()) {
            logger.warn("Ignoring an INSTALL_PLUGIN with no server uuid or task id.")

            return
        }

        try {
            run(uuid, taskId, message)
        } catch (exception: Exception) {
            logger.error("Plugin install on $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, KIND, exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun run(uuid: String, taskId: String, message: InstallPluginMessage) {
        val filename = message.filename?.trim().orEmpty()
        val targetDir = message.targetDir?.trim().orEmpty()
        // Third-party downloads are absolute and pass straight through; a path means Pano is
        // serving the file itself and only this node knows where to reach Pano.
        val downloadUrl = platformUrls.resolve(message.downloadUrl).orEmpty()

        // A filename is a single path segment and nothing else: it is the one field of this
        // message that becomes a real name on disk, and `../../start.sh` would otherwise be a
        // write anywhere the daemon can reach.
        if (!isJarName(filename)) {
            reporter.failed(taskId, uuid, KIND, "\"$filename\" is not a usable jar file name.")

            return
        }

        if (targetDir !in ALLOWED_TARGET_DIRS) {
            reporter.failed(taskId, uuid, KIND, "Plugins may only be installed into plugins/ or mods/.")

            return
        }

        if (downloadUrl.isEmpty()) {
            reporter.failed(taskId, uuid, KIND, "Pano did not resolve a download for this version.")

            return
        }

        val replaceFilename = message.replaceFilename?.trim()?.takeIf { it.isNotEmpty() }

        if (replaceFilename != null && !isJarName(replaceFilename)) {
            reporter.failed(taskId, uuid, KIND, "\"$replaceFilename\" is not a usable jar file name.")

            return
        }

        val server = registry.get(uuid)

        if (server == null) {
            reporter.failed(taskId, uuid, KIND, "This node does not have that server.")

            return
        }

        val directory = PathSafety.resolveRelative(server.directory, targetDir)

        directory.mkdirs()

        val target = File(directory, filename)
        val part = File(directory, "$filename$PART_SUFFIX")

        part.delete()

        reporter.running(taskId, uuid, KIND, START_PERCENT, "Downloading $filename")

        try {
            Downloader.download(downloadUrl, part) { fraction ->
                val percent = START_PERCENT + (fraction * (VERIFY_PERCENT - START_PERCENT)).toInt()

                reporter.running(taskId, uuid, KIND, percent, "Downloading $filename")
            }

            reporter.running(taskId, uuid, KIND, VERIFY_PERCENT, "Verifying $filename")

            verify(part, message)

            if (!Downloader.isZip(part)) {
                throw IllegalStateException("The download is not a jar file.")
            }

            move(part, target)
        } catch (exception: Exception) {
            part.delete()

            throw exception
        }

        // Last, and only on success: the old jar is the fallback while anything can still fail.
        if (replaceFilename != null && replaceFilename != filename) {
            val previous = File(directory, replaceFilename)

            if (previous.isFile && !previous.delete()) {
                logger.warn("Installed $filename but could not remove the replaced $replaceFilename.")
            }
        }

        server.emit(ConsoleLevel.INFO, "Pano installed $filename into $targetDir (restart to load it).")

        reporter.done(taskId, uuid, KIND, "Installed $filename")
    }

    /**
     * Checks the download against whichever hash the source published.
     *
     * Absent hashes are not an error: Hangar's external downloads carry none at all, and refusing
     * those would mean refusing half of Hangar. The zip check downstream is what every download
     * gets regardless.
     */
    private fun verify(file: File, message: InstallPluginMessage) {
        val checks = listOf(
            "SHA-512" to message.sha512,
            "SHA-256" to message.sha256,
            "SHA-1" to message.sha1
        )

        checks.forEach { (algorithm, expected) ->
            if (expected.isNullOrBlank()) {
                return@forEach
            }

            if (!FileHash.matches(file, algorithm, expected)) {
                throw IllegalStateException("The download did not match its $algorithm checksum.")
            }
        }
    }

    /** Moves the finished download onto its real name, atomically where the filesystem allows it. */
    private fun move(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** The `TASK_PROGRESS` kind Pano records these under. */
        const val KIND = "PLUGIN_INSTALL"

        const val PART_SUFFIX = ".part"

        private const val START_PERCENT = 5
        private const val VERIFY_PERCENT = 90
        private const val LINK_PERCENT = 20

        private val ALLOWED_TARGET_DIRS = setOf("plugins", "mods")

        /**
         * Whether [name] is one path segment ending in `.jar`.
         *
         * Extension included deliberately: a server loads what is in its plugins directory, so the
         * only file that belongs there is the one it can load.
         */
        fun isJarName(name: String?): Boolean {
            if (!PathSafety.isSafeSegment(name)) {
                return false
            }

            val value = name!!

            if (!value.lowercase().endsWith(".jar") || value.length <= ".jar".length) {
                return false
            }

            return value.length <= MAX_NAME_LENGTH && value.none { it.isISOControl() }
        }

        const val MAX_NAME_LENGTH = 200
    }
}
