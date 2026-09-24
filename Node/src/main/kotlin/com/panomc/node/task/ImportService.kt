package com.panomc.node.task

import com.panomc.node.agent.AgentFiles
import com.panomc.node.agent.AgentLaunchReader
import com.panomc.node.agent.AgentLayout
import com.panomc.node.console.ConsoleLevel
import com.panomc.node.files.TransferService
import com.panomc.node.files.ZipTool
import com.panomc.node.net.ImportServerMessage
import com.panomc.node.net.ImportServerSpec
import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProperties
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * Turns somebody's existing server into a managed one (`IMPORT_SERVER`).
 *
 * Three ways in, one way out. A folder already on this host is copied, an uploaded archive is
 * fetched and unpacked, and a Modrinth modpack is downloaded and assembled — and all three then
 * go through the same inspection, because after the files are in place Pano knows no more about
 * what it has than it did before, and the only honest source of "this is Paper 1.21.8" is the
 * directory itself.
 *
 * A fourth way in is not a copy at all: `IN_PLACE` adopts a directory where it already is (the
 * "Pano Agent" link). It shares the inspection and everything after it, and it is the one mode the
 * two rules below do not describe — see [importInPlace] for the rules it keeps instead.
 *
 * Two rules the whole feature rests on:
 *
 * **A folder import copies, never moves.** Somebody pointing Pano at `/home/ben/smp` is telling it
 * where their server is, not giving it away. A move that goes wrong halfway destroys the original,
 * and there is no undo for that; a copy that goes wrong wastes disk.
 *
 * **Everything lands under the servers root and nowhere else.** The source path comes from a panel
 * form, the archive entries come from a file somebody uploaded, and the modpack paths come off the
 * internet, so each one is resolved through [PathSafety] against the new server's directory rather
 * than trusted.
 */
class ImportService(
    private val registry: ServerRegistry,
    private val reporter: TaskSink,
    private val connection: PlatformConnection,
    private val transferService: TransferService,
    private val loaderInstaller: LoaderInstaller,
    private val logger: NodeLogger,
    private val dataDir: File,
    private val reservations: PortReservations,
    private val portResolver: PortResolver,
    /** What an in-place adoption is checked against; replaced in tests. */
    private val adoptionHost: InPlaceAdoption.Host = InPlaceAdoption.Host(dataDir),
    /**
     * Whether this daemon is dedicated to one server (agent mode) and, if so, which directory.
     * An agent refuses every import but the in-place adoption of that directory, and that one only
     * while it has no server yet.
     */
    private val agentServer: () -> String? = { null },
    /** Called with a server the moment it has been adopted in place; an agent starts it (SM-74). */
    private val onAdopted: (ServerProcess) -> Unit = {}
) {
    fun import(message: ImportServerMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId
        val spec = message.spec

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank() || spec == null) {
            logger.warn("Ignoring an IMPORT with no server uuid, task id or spec.")

            return
        }

        if (!PathSafety.isSafeSegment(uuid)) {
            reporter.failed(taskId, uuid, KIND, "The server id is not a usable directory name.")

            return
        }

        val inPlace = message.mode?.uppercase() == MODE_IN_PLACE

        agentRefusal(uuid, message, inPlace)?.let { refusal ->
            refuse(taskId, uuid, AGENT_SINGLE_SERVER, refusal)

            return
        }

        // Checked before anything else, and for every mode: the directory of a server adopted in
        // place is somebody's real server, and every copy mode below starts by wiping the target.
        if (registry.isInPlace(uuid) || (inPlace && registry.get(uuid) != null)) {
            refuse(taskId, uuid, InPlaceAdoption.ALREADY_MANAGED, "This node already runs a server with that id.")

            return
        }

        if (inPlace) {
            importInPlace(uuid, taskId, message, spec)

            return
        }

        val directory = try {
            registry.directoryFor(uuid)
        } catch (exception: Exception) {
            reporter.failed(taskId, uuid, KIND, exception.message ?: "Rejected the server directory.")

            return
        }

        // Claimed up front for the same reason an install claims one: copying a server folder
        // takes long enough for another task to be handed the same port in the meantime.
        reservations.reserve(uuid, spec.port)

        try {
            runImport(uuid, taskId, message, spec, directory)
        } catch (exception: Exception) {
            logger.error("Import of $uuid failed: ${exception.message}", exception)

            // The half-built directory goes with the failure: a directory with no jar is not a
            // server somebody can retry from, it is one the next boot would try to load.
            directory.deleteRecursively()

            reporter.failed(taskId, uuid, KIND, exception.message ?: exception.javaClass.simpleName)
        } finally {
            reservations.release(uuid)
        }
    }

    private fun runImport(
        uuid: String,
        taskId: String,
        message: ImportServerMessage,
        spec: ImportServerSpec,
        directory: File
    ) {
        reporter.running(taskId, uuid, KIND, START_PERCENT, "Preparing")

        registry.get(uuid)?.shutdown()

        directory.deleteRecursively()
        directory.mkdirs()

        var packVersion: String? = null
        var packLoader: String? = null

        when (message.mode?.uppercase()) {
            MODE_FOLDER -> importFolder(uuid, taskId, message.folderPath, directory)

            MODE_UPLOAD -> importUpload(uuid, taskId, message.ticket, directory)

            MODE_MODPACK -> {
                val pack = importModpack(uuid, taskId, message, directory)

                packVersion = pack?.minecraftVersion
                packLoader = pack?.loader
            }

            else -> throw IllegalStateException("Pano asked for an import mode this node does not know.")
        }

        reporter.running(taskId, uuid, KIND, INSPECT_PERCENT, "Inspecting the server")

        val inspection = ServerInspection.inspect(directory, packVersion, packLoader)

        val jar = inspection.jar
            ?: throw IllegalStateException("No server jar was found in what was imported.")

        // The probe matters more here than on a fresh install: the directory being imported was
        // very likely running on this host a moment ago, and the address it used may now belong to
        // something else entirely.
        val port = portResolver.resolve(uuid, spec.port, inspection.port)
            ?: throw IllegalStateException("No free port left in the configured range.")

        val properties = LinkedHashMap<String, String>()

        spec.properties?.forEach { (key, value) ->
            if (key != "server-port") {
                properties[key] = value
            }
        }

        properties["server-port"] = port.toString()

        ServerProperties.merge(File(directory, "server.properties"), properties)

        // Only when Pano says a person accepted it, and never when the directory already carries
        // an acceptance — an imported server that was already running had its EULA accepted by
        // whoever ran it, and rewriting the file would claim that decision for Pano.
        if (spec.acceptEula == true && !inspection.eulaAccepted) {
            File(directory, "eula.txt").writeText("# Accepted through Pano\neula=true\n")
        }

        val stored = ServerSpec(
            uuid = uuid,
            name = spec.name ?: uuid,
            software = inspection.software ?: "",
            version = inspection.version ?: "",
            javaMajor = spec.javaMajor ?: inspection.javaMajor ?: ServerSpec.AUTO_JAVA_MAJOR,
            memoryMb = spec.memoryMb ?: ServerSpec.DEFAULT_MEMORY_MB,
            jvmArgs = spec.jvmArgs.orEmpty(),
            port = port,
            jar = jar,
            autoStart = false,
            crashRestart = true,
            properties = properties
        )

        registry.writeSpec(directory, stored)

        val server = registry.register(uuid, directory, stored)

        server.emit(
            ConsoleLevel.INFO,
            "Pano imported this server (${stored.software.ifBlank { "unknown software" }} " +
                "${stored.version.ifBlank { "" }}) on port $port."
        )

        // Sent before the task ends, and only once the directory is registered: Pano answers this
        // by pushing the Pano plugin into a server it has just been told exists.
        connection.send(
            NodeProtocol.Outbound.IMPORT_RESULT,
            JsonObject()
                .put("serverUuid", uuid)
                .put("taskId", taskId)
                .put("jar", jar)
                .put("software", inspection.software)
                .put("version", inspection.version)
                .put("port", port)
                .put("javaMajor", inspection.javaMajor)
        )

        logger.info("Imported server $uuid (${stored.software} ${stored.version}) on port $port.")

        reporter.done(taskId, uuid, KIND, "Imported ${stored.software.ifBlank { "a server" }} ${stored.version}")
    }

    /**
     * Adopts [ImportServerMessage.folderPath] where it is (`IN_PLACE`).
     *
     * The folder import's pipeline without its first half: no copy and, above all, no
     * `deleteRecursively` — not of the directory, not on a failure, not ever. The rules it keeps
     * instead:
     *
     * - the folder is checked by [InPlaceAdoption] before a single byte is written into it;
     * - nothing of the admin's is rewritten that does not have to be: `server.properties` is only
     *   touched when the port has to change, and `eula.txt` only when Pano says a person accepted
     *   it and the directory has no acceptance of its own;
     * - the port stays the one in `server.properties` unless Pano named another or it is taken now
     *   (it was just checked that no server runs from here, so "taken" means somebody else holds it);
     * - a failure puts back exactly what this import changed — `server.properties` as it was, and
     *   the `server.json` it wrote — and leaves everything else alone.
     *
     * Everything after that is the folder import's: the same `server.json`, the same
     * `IMPORT_RESULT` (with `inPlace` and `directory` added), and Pano links the plugin the same way.
     */
    private fun importInPlace(uuid: String, taskId: String, message: ImportServerMessage, spec: ImportServerSpec) {
        reporter.running(taskId, uuid, KIND, START_PERCENT, "Checking the server folder")

        // The jar a Pano Agent's admin chose on its first run (SM-76), when this is that agent.
        val chosenJar = if (agentServer() != null) AgentLaunchReader.read(dataDir)?.jar else null

        val directory = try {
            InPlaceAdoption.check(message.folderPath, registry, adoptionHost, chosenJar)
        } catch (refused: InPlaceAdoption.Refused) {
            logger.warn("Could not link the server in ${message.folderPath}: ${refused.message}")

            reporter.failed(taskId, uuid, KIND, refused.message ?: refused.code, JsonObject().put("errorCode", refused.code))

            return
        }

        reservations.reserve(uuid, spec.port)

        val propertiesFile = File(directory, "server.properties")
        val specFile = File(directory, ServerRegistry.SPEC_FILE)
        val eulaFile = File(directory, "eula.txt")

        // Snapshots of the two files this may rewrite, so a failure can put them back byte for byte.
        val originalProperties = propertiesFile.takeIf { it.isFile }?.readBytes()
        val originalEula = eulaFile.takeIf { it.isFile }?.readBytes()

        var registered = false

        try {
            reporter.running(taskId, uuid, KIND, INSPECT_PERCENT, "Inspecting the server")

            // The chosen jar, never the agent itself, and only one that is in the folder.
            val chosen = chosenJar?.takeUnless { AgentFiles.isOwnJar(directory, it) || AgentLayout.isAgentJarName(it) }

            val inspection = ServerInspection.inspect(directory, chosenJar = chosen)

            val jar = inspection.jar
                ?: throw InPlaceAdoption.Refused(InPlaceAdoption.NO_SERVER_JAR, "That folder holds no server jar.")

            val requested = spec.port?.takeIf { it in 1..65535 }

            // Pano's number when it sent one (the admin typed it, or an older Pano allocated it),
            // the directory's own otherwise; either way probed, and only swapped when it is taken.
            val port = portResolver.resolve(uuid, requested, inspection.port)
                ?: throw IllegalStateException("No free port left in the configured range.")

            val properties = LinkedHashMap<String, String>()

            properties["server-port"] = port.toString()

            // Only when it changes: a proxy has no server.properties at all, and a server whose port
            // stays what it was has no reason to have its file rewritten by somebody else.
            if (port != inspection.port) {
                ServerProperties.merge(propertiesFile, properties)
            }

            if (spec.acceptEula == true && !inspection.eulaAccepted) {
                eulaFile.writeText("# Accepted through Pano\neula=true\n")
            }

            val stored = ServerSpec(
                uuid = uuid,
                name = spec.name ?: directory.name,
                software = inspection.software ?: "",
                version = inspection.version ?: "",
                javaMajor = spec.javaMajor ?: inspection.javaMajor ?: ServerSpec.AUTO_JAVA_MAJOR,
                memoryMb = spec.memoryMb ?: ServerSpec.DEFAULT_MEMORY_MB,
                jvmArgs = spec.jvmArgs.orEmpty(),
                port = port,
                jar = jar,
                // What Pano's row says when it says (a Pano Agent's row starts on, SM-74), and for an
                // older Pano on an agent, on: the agent is how this server is started now.
                autoStart = spec.autoStart ?: (agentServer() != null),
                crashRestart = true,
                properties = properties,
                inPlace = true
            )

            registry.writeSpec(directory, stored)

            val server = registry.registerInPlace(uuid, directory, stored)

            registered = true

            server.emit(
                ConsoleLevel.INFO,
                "Pano adopted this server where it is (${stored.software.ifBlank { "unknown software" }} " +
                    "${stored.version.ifBlank { "" }}) on port $port."
            )

            connection.send(
                NodeProtocol.Outbound.IMPORT_RESULT,
                JsonObject()
                    .put("serverUuid", uuid)
                    .put("taskId", taskId)
                    .put("jar", jar)
                    .put("software", inspection.software)
                    .put("version", inspection.version)
                    .put("port", port)
                    .put("javaMajor", inspection.javaMajor)
                    .put("inPlace", true)
                    .put("directory", directory.absolutePath)
            )

            logger.notice(
                "Linked the server in ${directory.absolutePath} to Pano (${stored.software.ifBlank { "unknown software" }} " +
                    "${stored.version}, port $port)."
            )

            reporter.done(
                taskId,
                uuid,
                KIND,
                "Linked ${stored.software.ifBlank { "a server" }} ${stored.version} in ${directory.absolutePath}",
                JsonObject().put("inPlace", true).put("directory", directory.absolutePath)
            )

            try {
                onAdopted(server)
            } catch (exception: Exception) {
                logger.warn("Could not start ${directory.absolutePath} after linking it: ${exception.message}")
            }
        } catch (exception: Exception) {
            logger.error("Adopting $uuid at ${directory.absolutePath} failed: ${exception.message}", exception)

            // Only what this import itself wrote, and only if it did not end up registered: a
            // registered server is a server, whatever went wrong after it.
            if (!registered) {
                restore(propertiesFile, originalProperties)
                restore(eulaFile, originalEula)

                specFile.delete()
            }

            val code = (exception as? InPlaceAdoption.Refused)?.code

            reporter.failed(
                taskId,
                uuid,
                KIND,
                exception.message ?: exception.javaClass.simpleName,
                code?.let { JsonObject().put("errorCode", it) }
            )
        } finally {
            reservations.release(uuid)
        }
    }

    /** Puts [file] back to [original], or removes it when there was none. Never throws. */
    private fun restore(file: File, original: ByteArray?) {
        try {
            if (original == null) file.delete() else file.writeBytes(original)
        } catch (exception: Exception) {
            logger.warn("Could not restore ${file.absolutePath}: ${exception.message}")
        }
    }

    /**
     * Why an agent-mode daemon refuses [message], or null when it may run it: one adoption of the
     * directory it was installed for, and nothing else — ever. Pano should never ask for anything
     * more, and this is where "should" becomes "cannot".
     */
    private fun agentRefusal(uuid: String, message: ImportServerMessage, inPlace: Boolean): String? {
        val dedicated = agentServer()?.takeIf { it.isNotBlank() } ?: return null

        if (!inPlace) {
            return "This Pano Agent runs one existing server and cannot import another."
        }

        if (registry.all().any { it.uuid != uuid }) {
            return "This Pano Agent already runs its server."
        }

        val asked = message.folderPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "This Pano Agent runs the server in $dedicated only."

        return if (samePath(asked, dedicated)) null else "This Pano Agent runs the server in $dedicated only."
    }

    private fun samePath(first: String, second: String): Boolean {
        fun real(path: String) = try {
            File(path).toPath().toRealPath()
        } catch (_: Exception) {
            File(path).toPath().toAbsolutePath().normalize()
        }

        return real(first) == real(second)
    }

    /** Fails [taskId] as `"<CODE>: <sentence>"`, with the code in `errorCode` as well. */
    private fun refuse(taskId: String, uuid: String, code: String, sentence: String) {
        logger.warn("Refused IMPORT_SERVER for $uuid: $code: $sentence")

        reporter.failed(taskId, uuid, KIND, "$code: $sentence", JsonObject().put("errorCode", code))
    }

    /**
     * Copies a directory that is already on this host into the servers root.
     *
     * Refused when the source is inside the node's own data directory, because that is either this
     * very server, another managed one, or a backup — and "import" would then mean duplicating
     * something the node already owns under a second identity it would supervise twice.
     */
    private fun importFolder(uuid: String, taskId: String, folderPath: String?, directory: File) {
        val path = folderPath?.trim().orEmpty()

        if (path.isEmpty()) {
            throw IllegalStateException("No folder was given to import from.")
        }

        val source = File(path).absoluteFile.normalize()

        if (!source.isDirectory) {
            throw IllegalStateException("There is no directory at that path on this node.")
        }

        val dataPath = dataDir.absoluteFile.normalize().toPath()

        if (source.toPath().startsWith(dataPath) || dataPath.startsWith(source.toPath())) {
            throw IllegalStateException("That folder is inside this node's own data directory.")
        }

        if (ServerInspection.findServerJar(source) == null) {
            throw IllegalStateException("That folder holds no server jar.")
        }

        reporter.running(taskId, uuid, KIND, COPY_PERCENT, "Copying ${source.name}")

        // Symlinks are followed by copyRecursively, so a link inside the source could pull in a
        // directory from anywhere on the host. Skipping them keeps the import to what the person
        // can actually see in the folder they named.
        source.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    return@forEach
                }

                val relative = ZipTool.relativeName(source, file)

                if (relative.isEmpty()) {
                    return@forEach
                }

                val target = PathSafety.resolveRelative(directory, relative)

                if (file.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()

                    file.copyTo(target, overwrite = true)
                }
            }
    }

    /** Pulls the archive the browser uploaded and unpacks it into the new server directory. */
    private fun importUpload(uuid: String, taskId: String, ticket: String?, directory: File) {
        val id = ticket?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No upload ticket came with this import.")

        reporter.running(taskId, uuid, KIND, DOWNLOAD_PERCENT, "Downloading the archive")

        val archive = File(directory, TEMP_ARCHIVE)

        try {
            transferService.fetch(id, archive)

            if (!ZipTool.isZip(archive)) {
                throw IllegalStateException("The uploaded file is not a zip archive.")
            }

            reporter.running(taskId, uuid, KIND, EXTRACT_PERCENT, "Extracting the archive")

            ZipTool.extract(directory, archive, directory)
        } finally {
            archive.delete()
        }

        // Zipping a server folder puts the folder in the zip, so almost every upload is one
        // directory deep. Lifting it is what makes the obvious thing to upload work.
        ZipTool.flattenSingleRoot(directory)
    }

    /** Downloads an `.mrpack`, assembles it, and installs the loader it names. */
    private fun importModpack(
        uuid: String,
        taskId: String,
        message: ImportServerMessage,
        directory: File
    ): ModpackIndex.Pack? {
        val url = message.downloadUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Pano did not resolve a download for this modpack.")

        reporter.running(taskId, uuid, KIND, DOWNLOAD_PERCENT, "Downloading the modpack")

        val archive = File(directory, TEMP_ARCHIVE)

        Downloader.download(url, archive) { }

        if (!ZipTool.isZip(archive)) {
            archive.delete()

            throw IllegalStateException("What Modrinth served is not a modpack archive.")
        }

        val unpacked = File(directory, TEMP_PACK_DIR)

        try {
            ZipTool.extract(unpacked, archive, unpacked)
        } finally {
            archive.delete()
        }

        val pack = ModpackIndex.parse(File(unpacked, MODPACK_INDEX).takeIf { it.isFile }?.readText())
            ?: throw IllegalStateException("This archive carries no readable modrinth.index.json.")

        // Overrides first, mods after: a pack that ships a configured mod in `overrides/` and the
        // same mod in `files[]` means the download to be the one that lands.
        applyOverrides(File(unpacked, OVERRIDES_DIR), directory)
        applyOverrides(File(unpacked, SERVER_OVERRIDES_DIR), directory)

        unpacked.deleteRecursively()

        downloadPackFiles(uuid, taskId, pack, directory)

        val loader = pack.loader
            ?: throw IllegalStateException("This modpack does not say which mod loader it needs.")

        loaderInstaller.install(directory, loader, pack.loaderVersion, pack.minecraftVersion) { percent, message ->
            reporter.running(taskId, uuid, KIND, percent, message)
        }

        return pack
    }

    private fun downloadPackFiles(uuid: String, taskId: String, pack: ModpackIndex.Pack, directory: File) {
        if (pack.files.isEmpty()) {
            return
        }

        pack.files.forEachIndexed { index, entry ->
            val target = PathSafety.resolveRelative(directory, entry.path)

            target.parentFile?.mkdirs()

            val percent = FILES_START_PERCENT +
                (index * (FILES_END_PERCENT - FILES_START_PERCENT)) / pack.files.size

            reporter.running(taskId, uuid, KIND, percent, "Downloading ${pack.files.size} pack files")

            var lastError: Exception? = null

            // A pack lists mirrors for a reason; one of them being down is the normal case, not a
            // failed import.
            val downloaded = entry.downloads.any { candidate ->
                try {
                    Downloader.download(candidate, target) { }

                    true
                } catch (exception: Exception) {
                    lastError = exception

                    false
                }
            }

            if (!downloaded) {
                throw IllegalStateException(
                    "Could not download ${entry.path}: ${lastError?.message ?: "no mirror answered"}."
                )
            }
        }
    }

    /** Copies an `overrides/` tree into the server directory, every entry path-checked. */
    private fun applyOverrides(source: File, directory: File) {
        if (!source.isDirectory) {
            return
        }

        source.walkTopDown().forEach { file ->
            val relative = ZipTool.relativeName(source, file)

            if (relative.isEmpty()) {
                return@forEach
            }

            val target = PathSafety.resolveRelative(directory, relative)

            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()

                file.copyTo(target, overwrite = true)
            }
        }
    }

    companion object {
        const val KIND = "IMPORT"

        const val MODE_FOLDER = "FOLDER"
        const val MODE_UPLOAD = "UPLOAD"
        const val MODE_MODPACK = "MODPACK"

        /** Adopt the folder where it is: no copy, and the directory is never the node's to delete. */
        const val MODE_IN_PLACE = "IN_PLACE"

        /** An agent-mode daemon asked for anything but its one server. */
        const val AGENT_SINGLE_SERVER = "AGENT_SINGLE_SERVER"

        const val MODPACK_INDEX = "modrinth.index.json"
        const val OVERRIDES_DIR = "overrides"
        const val SERVER_OVERRIDES_DIR = "server-overrides"

        private const val TEMP_ARCHIVE = ".pano-import.zip"
        private const val TEMP_PACK_DIR = ".pano-modpack"

        private const val START_PERCENT = 1
        private const val DOWNLOAD_PERCENT = 5
        private const val COPY_PERCENT = 10
        private const val EXTRACT_PERCENT = 30
        private const val FILES_START_PERCENT = 20
        private const val FILES_END_PERCENT = 58
        private const val INSPECT_PERCENT = 85
    }
}
