package com.panomc.node.task

import com.panomc.node.console.ConsoleLevel
import com.panomc.node.java.JavaNeed
import com.panomc.node.java.JavaRuntimeService
import com.panomc.node.net.BuildSpec
import com.panomc.node.net.InstallServerMessage
import com.panomc.node.net.InstallServerSpec
import com.panomc.node.net.PlatformUrls
import com.panomc.node.server.JarJavaRequirement
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ServerProperties
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * Turns `INSTALL_SERVER` into a directory that can actually be started.
 *
 * The order matters and is the whole design: get the jar first, because it is the only step that
 * can take minutes and the only one likely to fail, and write the files that make the directory a
 * server only once there is a jar to run. "Getting the jar" is a download for every software but
 * Spigot, which has none to download and is compiled here by [BuildToolsInstaller]; everything
 * after that point is identical, which is the point.
 *
 * A failure therefore leaves either nothing or a directory without a server.json, and neither is
 * mistaken for an installed server on the next boot.
 *
 * A reinstall is the same pipeline into a fresh directory, with what `spec.keep` names carried
 * over (worlds by default; plugins and configs too since SM-66, see [ReinstallCarryOver]). Wiping
 * the old directory in place and hoping the download works is how people lose worlds; the old
 * directory is kept until the new server is registered, and put back if anything fails.
 */
class InstallService(
    private val registry: ServerRegistry,
    private val reporter: TaskReporter,
    private val logger: NodeLogger,
    private val pluginInstaller: PanoPluginInstaller,
    private val reservations: PortReservations,
    private val portResolver: PortResolver,
    private val platformUrls: PlatformUrls,
    private val buildToolsInstaller: BuildToolsInstaller,
    /**
     * Installs the Java the new server will need when this host lacks it (SM-63, §2.4.28), as
     * part of this install's own progress. Null where nothing may be downloaded.
     */
    private val javaService: JavaRuntimeService? = null,
    /** The one directory an agent-mode daemon runs, or null for an ordinary node. */
    private val agentServer: () -> String? = { null }
) {
    fun install(message: InstallServerMessage, reinstall: Boolean) {
        val uuid = message.serverUuid
        val taskId = message.taskId
        val spec = message.spec
        val kind = if (reinstall) "REINSTALL" else "INSTALL"

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank() || spec == null) {
            logger.warn("Ignoring an $kind with no server uuid, task id or spec.")

            return
        }

        if (!PathSafety.isSafeSegment(uuid)) {
            reporter.failed(taskId, uuid, kind, "The server id is not a usable directory name.")

            return
        }

        if (PathSafety.hasTraversal(listOf(spec.name, spec.software, spec.version, spec.downloadUrl, spec.build?.rev))) {
            reporter.failed(taskId, uuid, kind, "The install spec carried a path traversal.")

            return
        }

        // An agent runs the one server it was installed for and installs nothing (A2).
        if (!agentServer().isNullOrBlank()) {
            reporter.failed(
                taskId,
                uuid,
                kind,
                "${ImportService.AGENT_SINGLE_SERVER}: This Pano Agent runs one existing server and cannot install another.",
                JsonObject().put("errorCode", ImportService.AGENT_SINGLE_SERVER)
            )

            return
        }

        // A server adopted in place is somebody's directory, not one this node made: a reinstall
        // builds `<uuid>.installing` next to the old directory and swaps the two, and "next to" an
        // adopted directory is a place the node has no business creating folders in. Refused
        // rather than half-supported; the panel hides the buttons for these servers.
        if (registry.isInPlace(uuid)) {
            reporter.failed(
                taskId,
                uuid,
                kind,
                "${InPlaceAdoption.IN_PLACE_UNSUPPORTED}: A server linked where it is cannot be reinstalled or " +
                    "switched to other software by Pano.",
                JsonObject().put("errorCode", InPlaceAdoption.IN_PLACE_UNSUPPORTED)
            )

            return
        }

        // Claimed before the download starts, not after it finishes: the minutes in between are
        // exactly when a second install or import walks the same range and picks the same number.
        reservations.reserve(uuid, spec.port)

        // An INSTALL_SERVER for a server this node already has is a reinstall, whatever it is
        // called. Pano sent reinstalls under that name until SM-66, and installing one straight
        // into the existing directory wiped it -- worlds included.
        val asReinstall = reinstall || registry.get(uuid) != null

        try {
            runInstall(uuid, taskId, spec, kind, asReinstall)
        } catch (exception: Exception) {
            logger.error("Install of $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, kind, exception.message ?: exception.javaClass.simpleName)
        } finally {
            // By now the port is either on a registered server or on nothing at all, and both are
            // answers the allocator can see for itself.
            reservations.release(uuid)
        }
    }

    private fun runInstall(
        uuid: String,
        taskId: String,
        spec: InstallServerSpec,
        kind: String,
        reinstall: Boolean
    ) {
        reporter.running(taskId, uuid, kind, 1, "Preparing")

        // Resolved against this node's own Pano address, so a download Pano serves itself works
        // from a host that has never heard of Pano's public hostname.
        val downloadUrl = platformUrls.resolve(spec.downloadUrl)

        // Spigot arrives with a build instead of a URL, which is not a spec missing its download:
        // it is the only install where this host makes the jar.
        val buildSpec = spec.build?.takeIf { it.tool.equals(BuildSpec.BUILDTOOLS, ignoreCase = true) }

        if (buildSpec == null && downloadUrl.isNullOrBlank()) {
            reporter.failed(taskId, uuid, kind, "Pano did not resolve a download for this version.")

            return
        }

        val finalDirectory = registry.directoryFor(uuid)

        val existing = registry.get(uuid)

        // A fresh install over a directory nobody registered has nothing to keep; a reinstall
        // leaves the old server alone until the new files are all there, so a failed download
        // costs nothing at all.
        if (existing != null && !reinstall) {
            existing.shutdown()
        }

        val workDirectory = if (reinstall) {
            PathSafety.resolveUnder(registry.serversRoot, "$uuid${ReinstallCarryOver.INSTALLING_SUFFIX}")
        } else {
            finalDirectory
        }

        workDirectory.deleteRecursively()
        workDirectory.mkdirs()

        val jarFile = File(workDirectory, ServerSpec.DEFAULT_JAR)

        if (buildSpec != null) {
            val revision = buildSpec.rev?.takeIf { it.isNotBlank() } ?: spec.version.orEmpty()

            // The installer reports its own phases across the same range a download would use, so
            // the panel sees one install with a slow middle rather than a different kind of task.
            buildToolsInstaller.install(
                serverDirectory = workDirectory,
                rev = revision,
                toolUrl = buildSpec.toolUrl,
                javaMajor = buildSpec.javaMajor,
                onProgress = { percent, message -> reporter.running(taskId, uuid, kind, percent, message) }
            )
        } else {
            reporter.running(taskId, uuid, kind, DOWNLOAD_START_PERCENT, "Downloading ${spec.software} ${spec.version}")

            // Not null: an install with neither a URL nor a build failed a dozen lines above.
            // Bytes, size and rate ride on the frames, so the panel can say "120 MB / 1 GB at 12 MB/s".
            Downloader.download(downloadUrl!!, jarFile, md5 = spec.md5, onBytes = { progress ->
                val percent = DOWNLOAD_START_PERCENT +
                    ((progress.fraction ?: 0.0) * (JAR_DOWNLOAD_END_PERCENT - DOWNLOAD_START_PERCENT)).toInt()

                reporter.transfer(taskId, uuid, kind, percent, "Downloading ${spec.software} ${spec.version}", progress)
            }) { }
        }

        if (!Downloader.isZip(jarFile)) {
            workDirectory.deleteRecursively()

            reporter.failed(
                taskId,
                uuid,
                kind,
                if (buildSpec != null) "The built file is not a jar." else "The downloaded file is not a jar."
            )

            return
        }

        // The Java this server will run on, fetched now rather than on its first start: the
        // install is what somebody is watching a progress bar for, and a server reported
        // installed should be one that can start.
        val javaWarning = provisionJava(taskId, uuid, kind, spec, jarFile)

        if (!reinstall) {
            finishInstall(uuid, taskId, spec, kind, workDirectory, finalDirectory, javaWarning, previous = null)

            return
        }

        // From here on the old server is stopped and its files are in play, so every failure has
        // to put it back: the directory is untouched until the swap, and the swap undoes itself.
        val previous = existing?.spec ?: registry.readSpec(finalDirectory)

        existing?.shutdown()

        try {
            if (spec.keep != null) {
                reporter.running(taskId, uuid, kind, CARRY_PERCENT, "Carrying over plugins and configuration")

                val copied = ReinstallCarryOver.copyKept(
                    oldDirectory = finalDirectory,
                    workDirectory = workDirectory,
                    keep = spec.keep,
                    oldSoftware = previous?.software,
                    newSoftware = spec.software
                )

                if (copied.isNotEmpty()) {
                    logger.info("Reinstall of $uuid carries over ${copied.joinToString(", ")}.")
                }
            }

            finishInstall(uuid, taskId, spec, kind, workDirectory, finalDirectory, javaWarning, previous)
        } catch (exception: Exception) {
            workDirectory.deleteRecursively()

            // The old directory is where it was (the swap rolls itself back), so the server this
            // node had is registered again and can start as if nothing had been tried -- unless
            // the new one was already registered, in which case the failure came after the swap
            // and the new server is the one on disk.
            if (previous != null && finalDirectory.isDirectory && registry.get(uuid) === existing) {
                registry.register(uuid, finalDirectory, previous)
            }

            throw exception
        }
    }

    /**
     * Everything after the jar and the Java: the Pano plugin, the EULA, the port, `server.json`,
     * and — for a reinstall ([previous] is the old server's spec, null on a fresh install) — the
     * swap into place. Reports DONE, or returns early after reporting FAILED; throws only for the
     * unexpected, which the caller turns into FAILED.
     */
    private fun finishInstall(
        uuid: String,
        taskId: String,
        spec: InstallServerSpec,
        kind: String,
        workDirectory: File,
        finalDirectory: File,
        javaWarning: String?,
        previous: ServerSpec?
    ) {
        val reinstall = workDirectory != finalDirectory

        reporter.running(taskId, uuid, kind, PLUGIN_PERCENT, "Installing the Pano plugin")

        // Before server.json on purpose: the jar and its config belong to the same "this directory
        // is a server" moment, and a directory that is registered but has no plugin would come up
        // unlinked with nothing to say why.
        val pluginOutcome = pluginInstaller.install(workDirectory, spec.panoPlugin)

        reporter.running(taskId, uuid, kind, DOWNLOAD_END_PERCENT, "Configuring")

        // Only when Pano says the admin accepted it. Writing it unconditionally would mean this
        // daemon accepted Mojang's licence on somebody's behalf.
        if (spec.acceptEula == true) {
            File(workDirectory, "eula.txt").writeText("# Accepted through Pano\neula=true\n")
        }

        // Probed rather than trusted: a port Pano reserved can still be held by something on this
        // host that Pano has never heard of, and writing it into server.properties anyway is how a
        // server ends up dying on bind twenty seconds after it was reported installed.
        val port = portResolver.resolve(uuid, spec.port)
            ?: run {
                // Thrown on a reinstall, so the old server is put back; reported here otherwise.
                if (reinstall) {
                    throw IllegalStateException("No free port left in the configured range.")
                }

                workDirectory.deleteRecursively()

                reporter.failed(taskId, uuid, kind, "No free port left in the configured range.")

                return
            }

        val properties = LinkedHashMap<String, String>()

        spec.properties?.forEach { (key, value) -> properties[key] = value }

        properties["server-port"] = port.toString()

        spec.name?.takeIf { it.isNotBlank() }?.let { properties.putIfAbsent("motd", it) }
        properties.putIfAbsent("max-players", DEFAULT_MAX_PLAYERS.toString())

        ServerProperties.merge(File(workDirectory, "server.properties"), properties)

        val stored = ServerSpec(
            uuid = uuid,
            name = spec.name ?: uuid,
            software = spec.software ?: "",
            version = spec.version ?: "",
            // Absent means Pano left the choice here; it is stored as such rather than resolved
            // now, so a Java installed later is picked up on the next start.
            javaMajor = spec.javaMajor ?: ServerSpec.AUTO_JAVA_MAJOR,
            memoryMb = spec.memoryMb ?: ServerSpec.DEFAULT_MEMORY_MB,
            jvmArgs = spec.jvmArgs.orEmpty(),
            port = port,
            jar = ServerSpec.DEFAULT_JAR,
            autoStart = previous?.autoStart ?: registry.get(uuid)?.spec?.autoStart ?: false,
            crashRestart = previous?.crashRestart ?: registry.get(uuid)?.spec?.crashRestart ?: true,
            properties = properties
        )

        registry.writeSpec(workDirectory, stored)

        val swap = if (reinstall) {
            reporter.running(taskId, uuid, kind, SWAP_PERCENT, "Restoring worlds")

            ReinstallCarryOver.swap(finalDirectory, workDirectory, spec.keep)
        } else {
            null
        }

        val server = try {
            registry.register(uuid, finalDirectory, stored)
        } catch (exception: Exception) {
            swap?.rollback()

            throw exception
        }

        // Registered: the new server is the one this node runs now, and the old directory it
        // replaced has done its job as the way back.
        swap?.commit(logger)

        swap?.worlds?.takeIf { it.isNotEmpty() }?.let {
            server.emit(ConsoleLevel.INFO, "Pano carried over ${it.joinToString(", ")}.")
        }

        server.emit(ConsoleLevel.INFO, "Pano installed ${stored.software} ${stored.version} on port $port.")

        when (pluginOutcome) {
            is PanoPluginInstaller.Outcome.Installed -> server.emit(
                ConsoleLevel.INFO,
                "Pano installed ${pluginOutcome.jar}; this server is already linked."
            )

            // Loud, and with the reason. This used to be swallowed inside the installer: the
            // server came up unlinked, the task said DONE, and the only trace was a line in the
            // node's own log file on somebody else's machine.
            is PanoPluginInstaller.Outcome.Failed -> server.emit(
                ConsoleLevel.WARN,
                "Pano could not install its Minecraft plugin: ${pluginOutcome.error}"
            )

            PanoPluginInstaller.Outcome.None -> if (spec.panoPlugin != null) {
                server.emit(ConsoleLevel.WARN, "Pano could not install its Minecraft plugin into this server.")
            }
        }

        javaWarning?.let { server.emit(ConsoleLevel.WARN, "Pano $it; the first start tries again.") }

        logger.info("Installed server $uuid (${stored.software} ${stored.version}) on port $port.")

        val installed = pluginOutcome is PanoPluginInstaller.Outcome.Installed

        val base = if (installed) {
            "Installed ${stored.software} ${stored.version} with the Pano plugin"
        } else {
            "Installed ${stored.software} ${stored.version}"
        }

        // The warnings belong on the task as well as in the console: a plugin that could not be
        // fetched is something somebody has to act on, and the install task is what the panel
        // shows them when it finishes.
        val warnings = listOfNotNull(
            (pluginOutcome as? PanoPluginInstaller.Outcome.Failed)
                ?.let { "the Pano plugin could not be installed: ${it.error}" },
            javaWarning
        )

        val summary = (listOf(base) + warnings).joinToString(" -- ")

        reporter.done(
            taskId,
            uuid,
            kind,
            summary,
            // Only when a plugin was actually asked for: vanilla has none, and reporting false
            // there would mean every vanilla install looked like a failure.
            spec.panoPlugin?.let { JsonObject().put("pluginInstalled", installed) }
        )
    }

    /**
     * Makes sure the Java this server needs is on the host, and returns a warning when it could
     * not be (null otherwise).
     *
     * The major is §2.4.28's: pinned, or the ladder's minimum for the version, raised to what the
     * jar just downloaded is compiled for. A failure is a warning on a successful install rather
     * than a failed one: the files are all there, the first start tries the download again, and
     * failing a ten-minute install over the last step would throw that work away.
     */
    private fun provisionJava(
        taskId: String,
        uuid: String,
        kind: String,
        spec: InstallServerSpec,
        jarFile: File
    ): String? {
        val service = javaService ?: return null

        if (!service.enabled) {
            return null
        }

        val needed = JavaNeed.neededMajor(spec.javaMajor, spec.version, JarJavaRequirement.of(jarFile))

        val outcome = service.ensure(needed) { percent, message ->
            val scaled = JAVA_START_PERCENT + percent * (JAVA_END_PERCENT - JAVA_START_PERCENT) / 100

            reporter.running(taskId, uuid, kind, scaled, message)
        }

        return (outcome as? JavaRuntimeService.Outcome.Failed)
            ?.let { "could not download Java $needed: ${it.message}" }
    }

    companion object {
        const val DOWNLOAD_START_PERCENT = 5

        /** Where the jar download ends, leaving room for a Java download before the plugin. */
        const val JAR_DOWNLOAD_END_PERCENT = 60
        const val JAVA_START_PERCENT = 60
        const val JAVA_END_PERCENT = 77
        const val PLUGIN_PERCENT = 78
        const val DOWNLOAD_END_PERCENT = 80
        const val SWAP_PERCENT = 90

        /** Copying the kept plugins and configs, after the Java and before the Pano plugin. */
        const val CARRY_PERCENT = 77
        const val DEFAULT_MAX_PLAYERS = 20
    }
}
