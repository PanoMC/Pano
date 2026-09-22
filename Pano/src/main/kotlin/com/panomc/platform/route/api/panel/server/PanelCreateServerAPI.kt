package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CreatedServerLog
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.TransferExpired
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NodeOutdated
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerImportService
import com.panomc.platform.node.ManagedServerInstallService
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.ServerPortAllocator
import com.panomc.platform.node.ServerStartupLimits
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.message.ImportMode
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.ServerCreateSource
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.software.ServerSoftwareCatalog
import com.panomc.platform.util.Aes256GcmUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.sqlclient.SqlClient
import java.util.UUID

/**
 * Creates a managed server on a node and starts its install or import.
 *
 * Five sources, one endpoint, because they differ only in where the files come from and the row
 * that has to exist first is identical. `FRESH` downloads the software Pano resolved;
 * `EXISTING_FOLDER`, `UPLOAD` and `MODPACK` hand the node a directory, an uploaded archive or a
 * Modrinth `.mrpack` and let it work out what it received. `IN_PLACE` hands it a directory too, but
 * to be run from where it is rather than copied (the "Pano Agent" link): the row is written with
 * `inPlace = true` and `directory` set, its files survive the server's removal, and it can be
 * neither reinstalled nor switched to other software. Only a node that announced protocol 5 is
 * sent one; an older node is refused here with `NODE_OUTDATED` before any row exists.
 *
 * `software` is required for a fresh install and optional for every import, and that asymmetry is
 * the point: a fresh install *is* the software somebody picked, while an import is somebody's
 * existing server whose software is a question the node answers in `IMPORT_RESULT`. A wizard that
 * pre-filled them is welcome to send them; they are treated as a starting value the node is free
 * to correct. `version` may always be left out: for a fresh install it becomes the software's
 * newest release (never a snapshot), and for an import the node's own answer.
 *
 * `javaMajor` may be left out or sent as null, which is the wizard's "automatic": the row keeps a
 * null and the node picks the newest runtime the Minecraft version can run on, because only the
 * node can see what is installed on that host.
 *
 * `port` may be left out, and then Pano allocates one with [ServerPortAllocator] and stores it on
 * the row before the node hears about this server at all. (Except for `IN_PLACE`: a server that is
 * already somebody's keeps the port in its `server.properties`, so the node is told to choose and
 * the allocation on the row is only a placeholder until `IMPORT_RESULT` writes the real one.) That is deliberate and
 * it is what stops two sides allocating the same number: the node is always told which port to
 * take, and picks one of its own only for somebody driving it by hand.
 *
 * The row is written before the node has done anything, in `INSTALLING`, so the panel has
 * something to navigate to and the work has somewhere to report progress. `permissionGranted`
 * is true from the start, unlike a linked server: there is no connect request to approve when Pano
 * is the one that created the thing.
 */
@Endpoint
class PanelCreateServerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val serverSoftwareCatalog: ServerSoftwareCatalog,
    private val managedServerInstallService: ManagedServerInstallService,
    private val managedServerImportService: ManagedServerImportService,
    private val transferTicketStore: TransferTicketStore,
    private val pluginSourceCatalog: PluginSourceCatalog
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/create", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("nodeId", numberSchema())
                        .requiredProperty("name", stringSchema().withKeyword("maxLength", 255))
                        // Required in spirit for a fresh install and checked as such below; the
                        // schema cannot express "required unless this is an import".
                        .optionalProperty("software", stringSchema().nullable())
                        .optionalProperty("version", stringSchema().nullable())
                        // Optional and nullable: the wizard's "automatic" is sent as a null, and
                        // the node is the one that knows which runtimes that host has.
                        .optionalProperty("javaMajor", intSchema().nullable())
                        .requiredProperty("memoryMb", intSchema())
                        .optionalProperty("port", intSchema().nullable())
                        .optionalProperty("jvmArgs", arraySchema().items(stringSchema()))
                        .optionalProperty("acceptEula", booleanSchema())
                        .optionalProperty("autoStart", booleanSchema())
                        .optionalProperty("crashRestart", booleanSchema())
                        // SM-69: the daily update check, on unless the wizard says otherwise.
                        .optionalProperty("autoUpdateCheck", booleanSchema())
                        .optionalProperty("source", stringSchema())
                        .optionalProperty("folderPath", stringSchema().nullable())
                        .optionalProperty("uploadTicket", stringSchema().nullable())
                        .optionalProperty(
                            "modpack",
                            objectSchema()
                                .optionalProperty("source", stringSchema())
                                .requiredProperty("projectId", stringSchema())
                                .requiredProperty("versionId", stringSchema())
                                .nullable()
                        )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val data = getParameters(context).body().jsonObject

        val nodeId = data.getLong("nodeId")
        val name = data.getString("name").trim().take(MAX_NAME_LENGTH)
        val software = data.getString("software")?.trim()?.takeIf { it.isNotEmpty() }
        val version = data.getString("version")?.trim()?.takeIf { it.isNotEmpty() }
        val javaMajor = data.getInteger("javaMajor")
        val memoryMb = data.getInteger("memoryMb")
        val port = data.getInteger("port")
        val jvmArgs = readJvmArgs(data.getJsonArray("jvmArgs"))
        val autoStart = data.getBoolean("autoStart", false)
        val crashRestart = data.getBoolean("crashRestart", true)
        val settings = initialSettings(data)

        val source = ServerCreateSource.fromId(data.getString("source"))
            ?: throw BadRequest(
                extras = mapOf("message" to "\"${data.getString("source")}\" is not a server source.")
            )

        if (name.isEmpty() ||
            (javaMajor != null && javaMajor !in MIN_JAVA_MAJOR..MAX_JAVA_MAJOR) ||
            memoryMb !in MIN_MEMORY_MB..MAX_MEMORY_MB ||
            (port != null && port !in MIN_PORT..MAX_PORT)
        ) {
            throw BadRequest()
        }

        // Mojang's EULA has to be accepted by a person, so a missing flag is a refusal rather than
        // something Pano quietly decides on their behalf.
        if (data.getBoolean("acceptEula", false) != true) {
            throw BadRequest()
        }

        // A fresh install is the one case where Pano must know the software: it is what decides
        // the download. An import discovers it, so an unresolvable name is simply left unset and
        // the row starts as VANILLA — the type with no plugin platform, which is the honest
        // placeholder until the node says otherwise.
        val serverType = if (source == ServerCreateSource.FRESH) {
            serverSoftwareCatalog.serverTypeOf(software ?: throw BadRequest()) ?: throw BadRequest()
        } else {
            software?.let { serverSoftwareCatalog.serverTypeOf(it) } ?: ServerType.VANILLA
        }

        // An omitted version is the wizard's "whatever you recommend", not a mistake: it resolves
        // to the newest *release*, which is the one thing "the newest version" was not -- a
        // Velocity created without one used to install `velocity 4.2.1-SNAPSHOT`, a build that
        // needs Java 25, and crash-loop on a host that has 21.
        val resolvedVersion = if (source == ServerCreateSource.FRESH) {
            version
                ?: serverSoftwareCatalog.recommendedVersion(software.orEmpty())
                ?: throw BadRequest(
                    extras = mapOf("message" to "No version of \"$software\" could be resolved.")
                )
        } else {
            version
        }

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: throw NotExists()

        if (!node.approved || !nodeManager.isConnected(nodeId)) {
            throw NodeOffline()
        }

        // A Pano Agent runs the one server it was installed for and refuses every other one
        // (AGENT_SINGLE_SERVER); it is not offered in the wizard, and not accepted from it either.
        if (node.agent) {
            throw BadRequest(extras = mapOf("message" to "A Pano Agent runs only the server it was installed for."))
        }

        val inPlace = source == ServerCreateSource.IN_PLACE

        // An older daemon fails an IN_PLACE import as "a mode this node does not know" -- after the
        // row exists. Refused before, where the panel can say the node needs an update.
        if (inPlace && !NodeProtocol.supportsInPlace(node.protocolVersion)) {
            throw NodeOutdated(extras = mapOf("requiredProtocolVersion" to NodeProtocol.IN_PLACE_VERSION))
        }

        val folderPath = if (inPlace || source == ServerCreateSource.EXISTING_FOLDER) {
            data.getString("folderPath")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PATH_LENGTH)
                ?: throw BadRequest(extras = mapOf("message" to "No folder was given to import from."))
        } else {
            null
        }

        // Reserved here, on the row, rather than left to the node. Both sides used to allocate:
        // Pano walked its own rows while the node walked its registry for a concurrent install,
        // and two servers were handed 25567 within seconds of each other. A port that is
        // written down before anything starts is a port the next allocation can see -- and the
        // node, told a number, no longer picks one at all. Inside the range the node announced,
        // when it did: on a node in a container those are the only ports anybody outside can reach.
        val nodePortRange = nodeManager.getPortRange(nodeId)

        val gamePort = port ?: ServerPortAllocator
            .allocate(
                1,
                ServerPortAllocator.takenPorts(databaseManager.serverDao.getAllByNodeId(nodeId, sqlClient)),
                nodePortRange
            )
            ?.firstOrNull()
            ?: throw BadRequest(extras = mapOf("message" to ServerPortAllocator.noFreePortMessage(nodePortRange)))

        val now = System.currentTimeMillis()

        val server = Server(
            name = name,
            motd = "",
            host = node.hostname ?: node.remoteAddress ?: DEFAULT_HOST,
            remoteAddress = node.remoteAddress,
            port = gamePort,
            playerCount = 0,
            maxPlayerCount = 0,
            type = serverType,
            version = resolvedVersion.orEmpty(),
            favicon = "",
            permissionGranted = true,
            status = ServerStatus.OFFLINE,
            addedTime = now,
            acceptedTime = now,
            startTime = 0,
            // Pre-generated so the row is complete; the plugin that is auto-installed into this
            // server later pairs with it instead of going through the connect handshake.
            aesKey = Aes256GcmUtil.generateBase64Key256(),
            kind = ServerKind.MANAGED,
            nodeId = nodeId,
            uuid = UUID.randomUUID().toString(),
            software = software,
            softwareVersion = resolvedVersion,
            javaVersion = javaMajor,
            memoryMb = memoryMb,
            jvmArgs = jvmArgs,
            gamePort = gamePort,
            autoStart = autoStart,
            crashRestart = crashRestart,
            processState = ServerProcessState.INSTALLING,
            settings = settings,
            inPlace = inPlace,
            // What the admin typed, until the node answers with the path it actually resolved.
            directory = if (inPlace) folderPath else null
        )

        val serverId = databaseManager.serverDao.add(server, sqlClient)

        val stored = databaseManager.serverDao.getById(serverId, sqlClient) ?: throw NotExists()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val task = if (source == ServerCreateSource.FRESH) {
            managedServerInstallService.start(
                server = stored,
                node = node,
                kind = ServerTaskKind.INSTALL,
                createdBy = userId,
                acceptEula = true,
                sqlClient = sqlClient
            )
        } else {
            startImport(source, data, stored, node, userId, folderPath, port, sqlClient)
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            CreatedServerLog(
                userId,
                username,
                serverId,
                nodeId,
                name,
                // An import has not identified its software yet, so the log records the source it
                // came from rather than inventing a name the node may contradict in a minute.
                software ?: source.name.lowercase(),
                resolvedVersion.orEmpty()
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "serverId" to serverId,
                "taskId" to task.id,
                "taskUuid" to task.uuid
            )
        )
    }

    /**
     * Hands one of the three import sources to the node, after checking the caller may use it.
     *
     * The checks are the point of this being a separate step: a folder path is an absolute path on
     * somebody else's machine, an upload ticket is a file spooled by *some* user, and a modpack id
     * is text the wizard sent. Each one is validated here, where the session is, rather than on
     * the node, which knows only that Pano asked.
     */
    private suspend fun startImport(
        source: ServerCreateSource,
        data: JsonObject,
        server: Server,
        node: Node,
        userId: Long,
        folderPath: String?,
        requestedPort: Int?,
        sqlClient: SqlClient
    ) = when (source) {
        ServerCreateSource.EXISTING_FOLDER -> managedServerImportService.start(
            server = server,
            node = node,
            mode = ImportMode.FOLDER,
            createdBy = userId,
            acceptEula = true,
            folderPath = folderPath ?: throw BadRequest(),
            sqlClient = sqlClient
        )

        // The node checks the folder itself (system folders, its own data directory, a server that
        // is still running there, one it already manages) and fails the task with the reason: only
        // it can see that host.
        ServerCreateSource.IN_PLACE -> managedServerImportService.start(
            server = server,
            node = node,
            mode = ImportMode.IN_PLACE,
            createdBy = userId,
            acceptEula = true,
            folderPath = folderPath ?: throw BadRequest(),
            port = requestedPort ?: ManagedServerInstallService.NODE_ALLOCATES_PORT,
            sqlClient = sqlClient
        )

        ServerCreateSource.UPLOAD -> {
            // Resolved against this session's user: the ticket is the only thing standing between
            // one person's upload and another person's import.
            val ticket = transferTicketStore.resolveSpool(data.getString("uploadTicket"), userId)
                ?: throw TransferExpired()

            transferTicketStore.bind(ticket, server.id, node.id, server.uuid.orEmpty())

            managedServerImportService.start(
                server = server,
                node = node,
                mode = ImportMode.UPLOAD,
                createdBy = userId,
                acceptEula = true,
                ticket = ticket.id,
                filename = ticket.fileName,
                sqlClient = sqlClient
            )
        }

        ServerCreateSource.MODPACK -> {
            val modpack = data.getJsonObject("modpack") ?: throw BadRequest()

            val modpackSource = modpack.getString("source") ?: MODPACK_SOURCE_MODRINTH

            if (!modpackSource.equals(MODPACK_SOURCE_MODRINTH, ignoreCase = true)) {
                throw BadRequest(
                    extras = mapOf("message" to "Pano can only import modpacks from Modrinth.")
                )
            }

            val projectId = modpack.getString("projectId").orEmpty()
            val versionId = modpack.getString("versionId").orEmpty()

            val resolved = pluginSourceCatalog.modpackVersion(projectId, versionId)
                ?: throw BadRequest(extras = mapOf("message" to "Modrinth no longer has that modpack version."))

            val file = pluginSourceCatalog.modpackFile(resolved)
            val url = file?.url
                ?: throw BadRequest(extras = mapOf("message" to "That modpack version publishes no archive."))

            managedServerImportService.start(
                server = server,
                node = node,
                mode = ImportMode.MODPACK,
                createdBy = userId,
                acceptEula = true,
                downloadUrl = url,
                filename = file.filename,
                sqlClient = sqlClient
            )
        }

        ServerCreateSource.FRESH -> throw BadRequest()
    }

    private fun readJvmArgs(array: JsonArray?): List<String> = ServerStartupLimits.jvmArgs((array ?: JsonArray()).list)

    companion object {
        /**
         * The settings blob a new server starts with: the defaults, plus the one preference the
         * wizard asks about (SM-69, §2.4.34). Every source goes through here -- a fresh install and
         * each of the three imports -- so an imported server honours the switch as well.
         */
        fun initialSettings(data: JsonObject) = Server.Companion.ServerSettings(
            autoUpdateCheck = data.getBoolean("autoUpdateCheck", true)
        )

        /** The only modpack directory Pano resolves today. */
        const val MODPACK_SOURCE_MODRINTH = "modrinth"

        private const val MAX_NAME_LENGTH = 255
        private const val MAX_PATH_LENGTH = 4096
        private const val MIN_JAVA_MAJOR = 8
        private const val MAX_JAVA_MAJOR = 64
        private const val MIN_MEMORY_MB = ServerStartupLimits.MIN_MEMORY_MB
        private const val MAX_MEMORY_MB = ServerStartupLimits.MAX_MEMORY_MB
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val DEFAULT_HOST = "127.0.0.1"
    }
}
