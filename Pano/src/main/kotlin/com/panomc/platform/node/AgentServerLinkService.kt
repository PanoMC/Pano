package com.panomc.platform.node

import com.panomc.platform.auth.panel.log.LinkedAgentServerLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.dto.AgentLaunchData
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import com.panomc.platform.node.message.ImportMode
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.util.Aes256GcmUtil
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.panomc.platform.server.ServerSelectionService

/**
 * Turns a Pano Agent's hello into its server (A2 of the "link an existing server" flow).
 *
 * An agent is a node dedicated to one existing server folder, paired with the agent code and never
 * listed as a node. It announces that folder in every hello (`agentServer`), and this is where Pano
 * acts on it:
 *
 * - **first hello, no server yet**: a MANAGED row is written for the agent's node -- `inPlace`, the
 *   folder as `directory`, named after the folder, in `INSTALLING`, and starting with its agent
 *   (`autoStart`: the agent is what the admin now starts in place of the server jar, SM-74), with
 *   the memory and Java arguments the admin gave the agent's first run (`agentLaunch`, SM-76, kept
 *   within [ServerStartupLimits] like the panel's own forms) -- and `IMPORT_SERVER` mode `IN_PLACE`
 *   is sent for it. From there it is the ordinary in-place adoption: the node checks the
 *   folder, answers `IMPORT_RESULT`, and Pano links the Pano plugin as for any import. The EULA is
 *   never accepted on anyone's behalf here: a server that was already running has its own.
 * - **a later hello that does not report the server** while no task is running for it: the
 *   adoption failed (typically `SERVER_RUNNING`: the old launcher was still up) and is sent again,
 *   so stopping the old launcher and restarting the agent is all it takes to finish the link.
 *
 * Nothing happens for an ordinary node, for an agent that names no folder, or for one older than
 * protocol 5.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AgentServerLinkService(
    private val databaseManager: DatabaseManager,
    private val managedServerImportService: ManagedServerImportService,
    private val agentNodeDirectory: AgentNodeDirectory,
    private val serverSelectionService: ServerSelectionService,
    private val logger: Logger
) {
    /** Agents whose server row is being written right now, so two quick hellos cannot both write one. */
    private val linking = ConcurrentHashMap.newKeySet<Long>()

    suspend fun onHello(node: Node, request: NodeHelloEventRequest) {
        if (!node.agent) {
            return
        }

        if (!linking.add(node.id)) {
            return
        }

        try {
            onAgentHello(node, request)
        } finally {
            linking.remove(node.id)
        }
    }

    private suspend fun onAgentHello(node: Node, request: NodeHelloEventRequest) {
        agentNodeDirectory.put(node)

        val folder = request.agentServer?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PATH_LENGTH)

        if (folder == null) {
            logger.warn("Pano Agent \"${node.name}\" (${node.id}) named no server folder; nothing to link.")

            return
        }

        if (!NodeProtocol.supportsInPlace(node.protocolVersion)) {
            logger.warn("Pano Agent \"${node.name}\" (${node.id}) is too old to link its server in place.")

            return
        }

        val sqlClient = databaseManager.getSqlClient()

        val servers = databaseManager.serverDao.getAllByNodeId(node.id, sqlClient)

        if (servers.isEmpty()) {
            link(node, folder, request.agentLaunch, request.portRange?.toRangeOrNull())

            return
        }

        val server = servers.first()
        val reported = request.servers.orEmpty().mapNotNull { it.uuid }.toSet()

        if (server.uuid == null || server.uuid in reported) {
            return
        }

        val busy = databaseManager.serverTaskDao.getAllUnfinished(sqlClient).any { it.serverId == server.id }

        if (busy) {
            return
        }

        logger.info("Pano Agent \"${node.name}\" (${node.id}) does not run its server yet; linking $folder again.")

        managedServerImportService.start(
            server = server,
            node = node,
            mode = ImportMode.IN_PLACE,
            createdBy = SYSTEM_USER,
            acceptEula = false,
            folderPath = folder,
            port = ManagedServerInstallService.NODE_ALLOCATES_PORT,
            sqlClient = sqlClient
        )
    }

    /**
     * Writes the server row for [node]'s folder and starts the in-place adoption. [portRange] is
     * the one the agent's hello announced, which the placeholder port is taken from.
     */
    private suspend fun link(node: Node, folder: String, launch: AgentLaunchData?, portRange: IntRange?) {
        val sqlClient = databaseManager.getSqlClient()
        val now = System.currentTimeMillis()

        val name = File(folder).name.ifBlank { node.name }.take(MAX_NAME_LENGTH)

        // A placeholder the IMPORT_RESULT replaces with the port in the folder's own
        // server.properties: the node is sent 0, so a server whose players know its address keeps it.
        val placeholderPort = ServerPortAllocator.allocate(1, emptySet(), portRange)?.firstOrNull() ?: DEFAULT_PORT

        val (memoryMb, jvmArgs) = launchSettings(launch)

        val server = Server(
            name = name,
            motd = "",
            host = node.hostname ?: node.remoteAddress ?: DEFAULT_HOST,
            remoteAddress = node.remoteAddress,
            port = placeholderPort,
            playerCount = 0,
            maxPlayerCount = 0,
            // What the node identifies replaces this the moment it answers.
            type = ServerType.VANILLA,
            version = "",
            favicon = "",
            permissionGranted = true,
            status = ServerStatus.OFFLINE,
            addedTime = now,
            acceptedTime = now,
            startTime = 0,
            aesKey = Aes256GcmUtil.generateBase64Key256(),
            kind = ServerKind.MANAGED,
            nodeId = node.id,
            uuid = UUID.randomUUID().toString(),
            memoryMb = memoryMb,
            jvmArgs = jvmArgs,
            gamePort = placeholderPort,
            // The agent replaced the server jar in the admin's start script, screen or hosting
            // panel: starting the agent has to start the server, as starting the jar did.
            autoStart = true,
            crashRestart = true,
            processState = ServerProcessState.INSTALLING,
            inPlace = true,
            directory = folder
        )

        val serverId = databaseManager.serverDao.add(server, sqlClient)
        val stored = databaseManager.serverDao.getById(serverId, sqlClient) ?: return

        val linker = agentNodeDirectory.takeLinker(node.id)

        managedServerImportService.start(
            server = stored,
            node = node,
            mode = ImportMode.IN_PLACE,
            createdBy = linker ?: SYSTEM_USER,
            acceptEula = false,
            folderPath = folder,
            port = ManagedServerInstallService.NODE_ALLOCATES_PORT,
            sqlClient = sqlClient
        )

        // The admin who issued the agent code lands on the server it linked, if they had none.
        linker?.let { serverSelectionService.selectIfNone(it, serverId, sqlClient) }

        val username = linker?.let { databaseManager.userDao.getUsernameFromUserId(it, sqlClient) }

        databaseManager.panelActivityLogDao.add(
            LinkedAgentServerLog(linker, username, serverId, node.id, name, folder),
            sqlClient
        )

        logger.info("Pano Agent \"${node.name}\" (${node.id}) connected; linking the server in $folder as \"$name\".")
    }

    companion object {
        /** Stands in for a task nobody in particular started. */
        const val SYSTEM_USER = -1L

        /**
         * The memory and Java arguments a new agent server's row starts with: what the agent's first
         * run reported ([launch]), kept within the bounds the create and startup-settings APIs
         * enforce, and Pano's defaults for whatever it did not report.
         */
        fun launchSettings(launch: AgentLaunchData?): Pair<Int, List<String>> = Pair(
            ServerStartupLimits.memoryMb(launch?.memoryMb) ?: ManagedServerInstallService.DEFAULT_MEMORY_MB,
            ServerStartupLimits.jvmArgs(launch?.jvmArgs)
        )

        private const val MAX_NAME_LENGTH = 255
        private const val MAX_PATH_LENGTH = 4096
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 25565
    }
}
