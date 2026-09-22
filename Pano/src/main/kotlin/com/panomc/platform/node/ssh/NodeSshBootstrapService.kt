package com.panomc.platform.node.ssh

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.NodeBootstrap
import com.panomc.platform.node.NodeBootstrapGrant
import com.panomc.platform.node.NodeBootstrapTokenStore
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Installs the node daemon on somebody else's machine, from the panel, over SSH.
 *
 * The alternative is telling an admin to paste a command into a terminal, which works and stays
 * supported — this exists because the same admin usually has the credentials right there and the
 * manual path is where a typo turns into an hour of debugging.
 *
 * Two phases with a human in between, because a first connection to an unknown host is exactly
 * where a man in the middle would sit. Phase one connects, records the host key and disconnects
 * without authenticating anything; the panel shows the fingerprint; phase two reconnects with
 * that fingerprint pinned and only then hands over a credential. Nothing is remembered: the host
 * key is pinned for this task and forgotten with it, and the password or private key is zeroed
 * the moment the session ends, successfully or not.
 *
 * The daemon pairs by itself afterwards with a bootstrap token minted here, so Pano never has to
 * poll the machine to find out whether the install worked: the node appearing *is* the result.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeSshBootstrapService(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val nodeBootstrapTokenStore: NodeBootstrapTokenStore,
    private val nodeInstallScriptProvider: NodeInstallScriptProvider,
    private val vertx: Vertx,
    private val logger: Logger
) {
    /**
     * What to connect to. Everything here came from a form and is treated as such.
     *
     * [panoUrl] is where *the node* should reach Pano, which is not always where a browser does:
     * see [com.panomc.platform.node.PanoUrlOverride]. Null means the website URL, which is the
     * right answer for every ordinary install.
     */
    data class Target(
        val host: String,
        val port: Int,
        val username: String,
        val sudo: Boolean,
        val name: String?,
        val panoUrl: String? = null
    )

    /**
     * How to prove who we are, held as character arrays so it can be wiped.
     *
     * A String would be interned and immortal; these are overwritten in [zero] as soon as the
     * session that used them is over, which is the most a JVM process can honestly promise about
     * a secret it was given.
     */
    class Credentials(
        val password: CharArray? = null,
        val privateKey: CharArray? = null,
        val passphrase: CharArray? = null
    ) {
        fun zero() {
            password?.fill('\u0000')
            privateKey?.fill('\u0000')
            passphrase?.fill('\u0000')
        }
    }

    /** A bootstrap that has shown its fingerprint and is waiting for somebody to accept it. */
    data class Pending(
        val taskId: Long,
        val taskUuid: String,
        val target: Target,
        val credentials: Credentials,
        val fingerprint: String,
        val createdBy: Long,
        val createdAt: Long
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Connects once to learn the host key, and stops there.
     *
     * No credential is sent in this phase. A host that is not the one the admin meant must not
     * see a password, and the whole point of showing the fingerprint first is that nothing has
     * been handed over by the time somebody looks at it.
     */
    suspend fun start(target: Target, credentials: Credentials, createdBy: Long): Pending {
        purgeStale()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = null,
            nodeId = null,
            kind = ServerTaskKind.NODE_BOOTSTRAP,
            status = ServerTaskStatus.RUNNING,
            percent = 5,
            message = "Connecting to ${target.username}@${target.host}:${target.port}",
            createdBy = createdBy
        )

        val sqlClient = databaseManager.getSqlClient()

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = ServerTask(
            id = taskId,
            uuid = task.uuid,
            nodeId = null,
            kind = task.kind,
            status = task.status,
            percent = task.percent,
            message = task.message,
            createdBy = createdBy,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )

        panelRealtimeHub.pushTaskProgress(stored)

        val fingerprint = try {
            vertx.executeBlocking<String> { readHostKey(target) }.coAwait()
        } catch (e: Exception) {
            credentials.zero()

            fail(stored, reason(e), sqlClient)

            throw e
        }

        val entry = Pending(
            taskId = taskId,
            taskUuid = task.uuid,
            target = target,
            credentials = credentials,
            fingerprint = fingerprint,
            createdBy = createdBy,
            createdAt = System.currentTimeMillis()
        )

        pending[task.uuid] = entry

        progress(stored, 10, "Host key fingerprint $fingerprint — waiting for confirmation", sqlClient)

        return entry
    }

    /** The waiting task with this uuid or numeric id, when it belongs to [userId]. */
    fun pendingFor(taskId: String, userId: Long): Pending? {
        purgeStale()

        val entry = pending[taskId] ?: pending.values.firstOrNull { it.taskId.toString() == taskId }

        return entry?.takeIf { it.createdBy == userId }
    }

    /**
     * Accepts the fingerprint and runs the install.
     *
     * Returns as soon as the session is under way: the install takes minutes and the panel
     * follows it on the task stream, which is the same shape every other long job here has.
     */
    suspend fun confirm(entry: Pending) {
        pending.remove(entry.taskUuid)

        val sqlClient = databaseManager.getSqlClient()

        val task = databaseManager.serverTaskDao.getByUuid(entry.taskUuid, sqlClient)
            ?: throw IllegalStateException("The bootstrap task disappeared.")

        val token = nodeBootstrapTokenStore.issue(NodeBootstrapGrant.remote(NodeBootstrap.SSH))

        val command = SshBootstrapCommand.build(
            panoUrl = nodeInstallScriptProvider.panoUrl(entry.target.panoUrl),
            code = token,
            sudo = entry.target.sudo,
            name = entry.target.name
        )

        // Rendered with the same address, so the host downloads the daemon from the Pano it can
        // actually reach rather than from one it cannot.
        val script = nodeInstallScriptProvider.shellScript(entry.target.panoUrl)

        progress(task, 20, "Installing on ${entry.target.host}", sqlClient)

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                val exitCode = vertx
                    .executeBlocking<Int> { run(entry, command, script, task) }
                    .coAwait()

                if (exitCode == 0) {
                    progress(
                        task,
                        95,
                        "Installed. Waiting for the node to pair.",
                        sqlClient,
                        ServerTaskStatus.DONE,
                        100
                    )
                } else {
                    fail(task, "The installer exited with code $exitCode.", sqlClient)

                    nodeBootstrapTokenStore.revoke(token)
                }
            } catch (e: Exception) {
                logger.warn("SSH bootstrap of ${entry.target.host} failed: ${e.message}")

                fail(task, reason(e), sqlClient)

                nodeBootstrapTokenStore.revoke(token)
            } finally {
                entry.credentials.zero()
            }
        }
    }

    /** Drops a task somebody started and never confirmed, zeroing what it was holding. */
    fun cancel(entry: Pending) {
        pending.remove(entry.taskUuid)

        entry.credentials.zero()
    }

    // ----------------------------------------------------------------------------------- ssh

    private fun readHostKey(target: Target): String {
        val client = newClient()

        var seen: String? = null

        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                seen = key?.let { SshHostKeyFingerprint.of(it) }

                // Accepted only far enough to learn the key: the connection is closed immediately
                // afterwards and no credential has been sent.
                return true
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })

        try {
            client.connect(target.host, target.port)
        } finally {
            closeQuietly(client)
        }

        return seen ?: throw IllegalStateException("The host offered no key.")
    }

    private fun run(entry: Pending, command: String, script: String, task: ServerTask): Int {
        val client = newClient()

        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean =
                key != null && SshHostKeyFingerprint.matches(entry.fingerprint, SshHostKeyFingerprint.of(key))

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })

        try {
            client.connect(entry.target.host, entry.target.port)

            authenticate(client, entry)

            val session = client.startSession()

            try {
                val exec = session.exec(command)

                // The script goes in over the same channel the command reads from, so the target
                // needs no outbound access to Pano and no curl.
                exec.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }

                val stderr = drainInBackground(exec.errorStream, task)

                drain(exec.inputStream, task)

                exec.join(TIMEOUT_MINUTES, TimeUnit.MINUTES)

                stderr.join(STREAM_JOIN_MS)

                return exec.exitStatus ?: -1
            } finally {
                closeQuietly(session)
            }
        } finally {
            closeQuietly(client)
        }
    }

    private fun authenticate(client: SSHClient, entry: Pending) {
        val credentials = entry.credentials

        when {
            credentials.privateKey != null -> {
                val keyProvider = client.loadKeys(
                    String(credentials.privateKey),
                    null,
                    credentials.passphrase?.let { PasswordUtils.createOneOff(it) }
                )

                client.authPublickey(entry.target.username, keyProvider)
            }

            credentials.password != null -> client.authPassword(entry.target.username, credentials.password)

            else -> throw IllegalArgumentException("No credentials were given.")
        }
    }

    private fun newClient(): SSHClient {
        val client = SSHClient()

        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = SOCKET_TIMEOUT_MS

        return client
    }

    private fun drain(stream: InputStream, task: ServerTask) {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
            emit(task, line)
        }
    }

    private fun drainInBackground(stream: InputStream, task: ServerTask): Thread {
        val thread = Thread {
            try {
                drain(stream, task)
            } catch (_: Exception) {
                // A closed stream at the end of a session is normal and says nothing useful.
            }
        }

        thread.isDaemon = true
        thread.start()

        return thread
    }

    /**
     * Sends one line of installer output to whoever is watching.
     *
     * Over the hub only, never into the row: the installer produces dozens of lines and the row
     * is the after-the-fact summary, not a log. The panel keeps the lines it saw live.
     */
    private fun emit(task: ServerTask, line: String) {
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            return
        }

        val snapshot = task.copy(message = trimmed.take(MAX_LINE_LENGTH), status = ServerTaskStatus.RUNNING)

        vertx.runOnContext { panelRealtimeHub.pushTaskProgress(snapshot) }
    }

    // --------------------------------------------------------------------------------- tasks

    private suspend fun progress(
        task: ServerTask,
        percent: Int,
        message: String,
        sqlClient: io.vertx.sqlclient.SqlClient,
        status: ServerTaskStatus = ServerTaskStatus.RUNNING,
        finalPercent: Int = percent
    ) {
        task.status = status
        task.percent = finalPercent
        task.message = message
        task.updatedAt = System.currentTimeMillis()

        databaseManager.serverTaskDao.updateProgressByUuid(
            uuid = task.uuid,
            status = task.status,
            percent = task.percent,
            message = task.message,
            error = task.error,
            updatedAt = task.updatedAt,
            sqlClient = sqlClient
        )

        panelRealtimeHub.pushTaskProgress(task)
    }

    private suspend fun fail(task: ServerTask, error: String, sqlClient: io.vertx.sqlclient.SqlClient) {
        task.status = ServerTaskStatus.FAILED
        task.error = error.take(MAX_LINE_LENGTH)
        task.updatedAt = System.currentTimeMillis()

        databaseManager.serverTaskDao.updateProgressByUuid(
            uuid = task.uuid,
            status = task.status,
            percent = task.percent,
            message = task.message,
            error = task.error,
            updatedAt = task.updatedAt,
            sqlClient = sqlClient
        )

        panelRealtimeHub.pushTaskProgress(task)
    }

    /** Forgets tasks nobody ever confirmed, so an abandoned form does not keep a password alive. */
    private fun purgeStale(now: Long = System.currentTimeMillis()) {
        pending.entries.removeIf { entry ->
            val stale = now - entry.value.createdAt > CONFIRM_TTL_MS

            if (stale) {
                entry.value.credentials.zero()
            }

            stale
        }
    }

    private fun reason(e: Exception) = e.message?.take(MAX_LINE_LENGTH) ?: e::class.java.simpleName

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** The whole install, including downloading a JRE on a slow host. */
        const val TIMEOUT_MINUTES = 10L

        /** How long a fingerprint may wait for somebody to look at it. */
        const val CONFIRM_TTL_MS = 10 * 60 * 1000L

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val STREAM_JOIN_MS = 2_000L
        private const val MAX_LINE_LENGTH = 500
    }
}
