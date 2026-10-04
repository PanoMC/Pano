package com.panomc.platform.node.coolify

import com.panomc.platform.Main
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
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deploys the node daemon to a Coolify instance the operator already runs.
 *
 * The SSH path installs onto a machine; this one hands the work to something that already knows
 * how to run containers on that machine — which is the difference between a node an operator has
 * to look after and one their platform looks after for them.
 *
 * Coolify is talked to over its REST API with a token the operator pastes in, and that token is
 * never stored: it exists for the duration of the deployment and nowhere else. Pano is not a
 * Coolify client in any lasting sense, it is a caller that asks for one application to exist.
 *
 * Like the SSH bootstrap, the result is not read back from Coolify: the daemon pairs by itself
 * with the bootstrap token it was given, and the node appearing in the list is the only proof
 * that matters. Polling the deployment is only there to say something useful while that happens,
 * and to give up after ten minutes rather than leaving a task running forever.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeCoolifyBootstrapService(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val nodeBootstrapTokenStore: NodeBootstrapTokenStore,
    private val nodeInstallScriptProvider: NodeInstallScriptProvider,
    private val webClient: WebClient,
    private val vertx: Vertx,
    private val logger: Logger
) {
    /**
     * Everything the operator filled in. The token is the only secret and is never persisted.
     *
     * [panoUrl] is what the container's `PANO_URL` becomes when the website URL is not how the
     * node reaches Pano — a Coolify host on the other side of NAT, or a tunnelled development
     * Pano. See [com.panomc.platform.node.PanoUrlOverride]; null means the website URL.
     */
    data class Request(
        val coolifyUrl: String,
        val apiToken: String,
        val serverUuid: String,
        val projectUuid: String,
        val environmentName: String,
        val environmentUuid: String?,
        val name: String,
        val dataVolume: String,
        val portRange: String,
        val panoUrl: String? = null,
        /**
         * The image to deploy, for an operator who does not pull from [IMAGE_NAME].
         *
         * A private registry, a mirror inside an air-gapped network, or a fork somebody builds
         * themselves — all of which are the normal reasons a Coolify host cannot reach ghcr.io.
         * Null is the published image.
         */
        val image: String? = null,
        /** The tag on [image]; null follows Pano's own version, as a matching daemon must. */
        val imageTag: String? = null
    )

    /**
     * Creates the application, gives it its configuration and deploys it.
     *
     * Everything up to the deploy is awaited, so a bad token or a project that does not exist is
     * a refusal the operator sees immediately rather than a task that fails a minute later. Only
     * the waiting is backgrounded.
     */
    suspend fun start(request: Request, createdBy: Long): ServerTask {
        val sqlClient = databaseManager.getSqlClient()

        val draft = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = null,
            nodeId = null,
            kind = ServerTaskKind.NODE_BOOTSTRAP,
            status = ServerTaskStatus.RUNNING,
            percent = 5,
            message = "Creating the application on Coolify",
            createdBy = createdBy
        )

        val taskId = databaseManager.serverTaskDao.add(draft, sqlClient)

        val task = draft.copy(id = taskId)

        panelRealtimeHub.pushTaskProgress(task)

        val token = nodeBootstrapTokenStore.issue(NodeBootstrapGrant.remote(NodeBootstrap.COOLIFY))

        val applicationUuid = try {
            val uuid = createApplication(request)

            progress(task, 40, "Configuring the node", sqlClient)

            setEnvironment(request, uuid, token)
            addDataVolume(request, uuid, task, sqlClient)

            progress(task, 60, "Deploying", sqlClient)

            deploy(request, uuid)

            uuid
        } catch (e: Exception) {
            nodeBootstrapTokenStore.revoke(token)

            val reason = reason(e)

            fail(task, reason, sqlClient)

            // The task already carries this, but the caller is still on the request that started
            // it and has nowhere to look yet, so the reason travels with the failure.
            throw BootstrapFailed(task, reason, e)
        }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                awaitPairing(request, applicationUuid, task, sqlClient)
            } catch (e: Exception) {
                logger.warn("Coolify bootstrap of ${request.name} could not be followed: ${e.message}")
            }
        }

        return task
    }

    // ------------------------------------------------------------------------------- coolify

    /**
     * `POST /api/v1/applications/dockerimage`.
     *
     * The image tag follows Pano's own version, because the daemon and the platform speak a
     * versioned protocol; `latest` is the fallback for a Pano that does not know its version,
     * which is a development build rather than anything an operator runs. Both halves can be
     * overridden, for the host that cannot pull from ghcr.io at all.
     *
     * The ports are sent as lists, not as the range the operator typed. Coolify validates both
     * fields against its own format and answers 422 for anything else — `ports_exposes` wants
     * bare numbers and `ports_mappings` wants `host:container` pairs, both comma separated — so
     * `25590-25595` was refused outright and no node was ever deployed with a range in the box.
     */
    private suspend fun createApplication(request: Request): String {
        val ports = expandPortRange(request.portRange)

        require(ports.isNotEmpty()) { "\"${request.portRange}\" is not a usable port range." }

        val body = JsonObject()
            .put("project_uuid", request.projectUuid)
            .put("server_uuid", request.serverUuid)
            .put("environment_name", request.environmentName)
            .put("docker_registry_image_name", request.image ?: IMAGE_NAME)
            .put("docker_registry_image_tag", request.imageTag ?: imageTag())
            .put("ports_exposes", portsExposes(ports))
            .put("ports_mappings", portsMappings(ports))
            .put("name", request.name)
            .put("description", "Pano node daemon")
            // Deployed explicitly below, so the environment and the volume are in place before
            // the container starts: a node that boots without its data directory pairs, writes
            // its keys somewhere ephemeral and loses them on the next deploy.
            .put("instant_deploy", false)

        request.environmentUuid?.let { body.put("environment_uuid", it) }

        val response = post(request, "/applications/dockerimage", body)

        return response.bodyAsJsonObject()?.getString("uuid")
            ?: throw IllegalStateException("Coolify created no application.")
    }

    /** `POST /api/v1/applications/{uuid}/envs`, one call per variable, as the API takes them. */
    private suspend fun setEnvironment(request: Request, applicationUuid: String, token: String) {
        val variables = environment(
            // Exactly what the operator gave, port and all: this is the address the container has
            // to reach Pano on, and a tunnel or a LAN Pano on a non-default port is precisely the
            // case the override exists for.
            panoUrl = nodeInstallScriptProvider.panoUrl(request.panoUrl),
            token = token,
            name = request.name,
            portRange = request.portRange
        )

        variables.forEach { (key, value) ->
            post(
                request,
                "/applications/$applicationUuid/envs",
                JsonObject()
                    .put("key", key)
                    .put("value", value)
                    // The token is a credential; Coolify hides a shown-once value in its UI
                    // after the first read, which is the most it offers here.
                    .put("is_shown_once", key == "PANO_BOOTSTRAP_TOKEN")
            )
        }
    }

    /**
     * `POST /api/v1/applications/{uuid}/storages` for `/data`.
     *
     * Not fatal when it fails. A node without a persistent volume works perfectly well until the
     * container is replaced, at which point it has lost its pairing and its servers — so the
     * failure is reported loudly on the task rather than silently, but it does not undo a
     * deployment that is otherwise fine.
     */
    private suspend fun addDataVolume(
        request: Request,
        applicationUuid: String,
        task: ServerTask,
        sqlClient: SqlClient
    ) {
        try {
            post(
                request,
                "/applications/$applicationUuid/storages",
                JsonObject()
                    .put("type", "persistent")
                    .put("name", request.dataVolume)
                    .put("mount_path", DATA_PATH)
            )
        } catch (e: Exception) {
            logger.warn("Could not attach ${request.dataVolume} to the Coolify application: ${e.message}")

            progress(
                task,
                50,
                "The node was deployed without a persistent volume; add one for $DATA_PATH in Coolify.",
                sqlClient
            )
        }
    }

    /** `POST /api/v1/deploy?uuid=…`. */
    private suspend fun deploy(request: Request, applicationUuid: String) {
        post(request, "/deploy?uuid=$applicationUuid", null)
    }

    /**
     * Says something useful until the node pairs, and gives up after ten minutes.
     *
     * The node pairing is what ends this, not Coolify reporting success: a deployment can be
     * green while the daemon inside it cannot reach Pano at all, and that distinction is the
     * whole reason somebody is watching this task.
     */
    private suspend fun awaitPairing(
        request: Request,
        applicationUuid: String,
        task: ServerTask,
        sqlClient: SqlClient
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val before = databaseManager.nodeDao.getAll(sqlClient).map { it.id }.toSet()

        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)

            val paired = databaseManager.nodeDao.getAll(sqlClient)
                .firstOrNull { it.id !in before && it.bootstrap == NodeBootstrap.COOLIFY }

            if (paired != null) {
                progress(task, 100, "The node \"${paired.name}\" paired.", sqlClient, ServerTaskStatus.DONE)

                return
            }

            val status = try {
                get(request, "/applications/$applicationUuid").bodyAsJsonObject()?.getString("status")
            } catch (_: Exception) {
                null
            }

            progress(task, 80, "Waiting for the node to pair" + (status?.let { " ($it)" } ?: ""), sqlClient)
        }

        fail(task, "The node did not pair within ${TIMEOUT_MS / 60_000} minutes.", sqlClient)
    }

    // --------------------------------------------------------------------------------- http

    private suspend fun post(request: Request, path: String, body: JsonObject?): HttpResponse<Buffer> {
        val call = prepare(webClient.postAbs(apiUrl(request, path)), request)

        val response = if (body == null) call.send().coAwait() else call.sendJsonObject(body).coAwait()

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Coolify answered ${response.statusCode()} for $path: ${errorOf(response)}")
        }

        return response
    }

    private suspend fun get(request: Request, path: String): HttpResponse<Buffer> {
        val response = prepare(webClient.getAbs(apiUrl(request, path)), request).send().coAwait()

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Coolify answered ${response.statusCode()} for $path")
        }

        return response
    }

    private fun <T> prepare(call: HttpRequest<T>, request: Request): HttpRequest<T> {
        call.timeout(REQUEST_TIMEOUT_MS)
        call.putHeader("Authorization", "Bearer ${request.apiToken}")
        call.putHeader("Accept", "application/json")

        return call
    }

    /**
     * Coolify's own words, because its 422s say exactly what was wrong.
     *
     * Both halves, not the first one that exists. Coolify's validation failures carry a `message`
     * of "Validation failed." and an `errors` map naming the fields — and the useless half is the
     * one that was being reported, which is how a rejected port format reached the operator as
     * "Validation failed." and nothing else.
     */
    private fun errorOf(response: HttpResponse<Buffer>): String = try {
        val body = response.bodyAsJsonObject()

        val parts = listOfNotNull(
            body?.getString("message")?.trim()?.takeIf { it.isNotEmpty() },
            describeErrors(body?.getJsonObject("errors"))
        )

        parts.joinToString(" ")
            .ifEmpty { response.bodyAsString().orEmpty() }
            .take(MAX_ERROR_LENGTH)
    } catch (_: Exception) {
        ""
    }

    // -------------------------------------------------------------------------------- tasks

    private suspend fun progress(
        task: ServerTask,
        percent: Int,
        message: String,
        sqlClient: SqlClient,
        status: ServerTaskStatus = ServerTaskStatus.RUNNING
    ) {
        task.status = status
        task.percent = percent
        task.message = message
        task.updatedAt = System.currentTimeMillis()

        write(task, sqlClient)
    }

    private suspend fun fail(task: ServerTask, error: String, sqlClient: SqlClient) {
        task.status = ServerTaskStatus.FAILED
        task.error = error.take(MAX_ERROR_LENGTH)
        task.updatedAt = System.currentTimeMillis()

        write(task, sqlClient)
    }

    private suspend fun write(task: ServerTask, sqlClient: SqlClient) {
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

    private fun reason(e: Exception) = e.message?.take(MAX_ERROR_LENGTH) ?: e::class.java.simpleName

    private fun imageTag(): String = Main.VERSION.takeIf { it.isNotBlank() && it != "null" } ?: FALLBACK_TAG

    private fun apiUrl(request: Request, path: String) = apiUrl(request.coolifyUrl, path)

    /** Coolify refused the deployment; [task] is the one already created for it. */
    class BootstrapFailed(
        val task: ServerTask,
        override val message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    companion object {
        const val IMAGE_NAME = "ghcr.io/panomc/pano-node"

        /** Where the daemon's data lives inside the image, as `Node/Dockerfile` declares it. */
        const val DATA_PATH = "/data"

        const val FALLBACK_TAG = "latest"

        const val TIMEOUT_MS = 10 * 60 * 1000L

        private const val POLL_INTERVAL_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val MAX_ERROR_LENGTH = 500

        /** As many ports as one node is given, however wide a range somebody typed. */
        const val MAX_PORTS = 64

        /**
         * `25590-25595` as the individual ports in it.
         *
         * Coolify takes a list, never a range, in either of the two port fields. A single port
         * stays a single entry; a reversed or unparseable second half degrades to just the first
         * port rather than to nothing, because one working port beats a refused deployment.
         */
        fun expandPortRange(range: String): List<Int> {
            val parts = range.trim().split('-', limit = 2)
            val from = port(parts.first()) ?: return emptyList()

            if (parts.size == 1) {
                return listOf(from)
            }

            val to = port(parts[1]) ?: return listOf(from)

            if (to < from) {
                return listOf(from)
            }

            // Capped rather than refused: a node does not need a thousand published ports, and
            // asking Docker for them would make the deployment itself fail.
            return (from..to).take(MAX_PORTS)
        }

        /**
         * The container's environment, in the order it is sent.
         *
         * `PANO_NODE_PORT_RANGE` is the range [createApplication] actually published (capped at
         * [MAX_PORTS], a single port as `p-p`), not what was typed. Without it the node never
         * knew: Pano walked up from 25565 and a server in a container publishing 25660-25669 was
         * put on 25565, healthy and unreachable. Left out only when nothing in [portRange] is a
         * port, which [createApplication] has already refused.
         */
        fun environment(panoUrl: String, token: String, name: String, portRange: String): List<Pair<String, String>> {
            val ports = expandPortRange(portRange)

            return listOfNotNull(
                "PANO_URL" to panoUrl,
                "PANO_BOOTSTRAP_TOKEN" to token,
                "PANO_NODE_NAME" to name,
                "PANO_NODE_DATA" to DATA_PATH,
                ports.takeIf { it.isNotEmpty() }?.let { "PANO_NODE_PORT_RANGE" to "${it.first()}-${it.last()}" }
            )
        }

        /** `ports_exposes`: bare numbers, comma separated. */
        fun portsExposes(ports: List<Int>): String = ports.joinToString(",")

        /**
         * `ports_mappings`: `host:container` pairs, comma separated.
         *
         * One to one, for the same reason the Docker runtime publishes a server's port one to
         * one: the number in `server.properties`, the number Pano stored and the number a player
         * types have to stay the same number.
         */
        fun portsMappings(ports: List<Int>): String = ports.joinToString(",") { "$it:$it" }

        /**
         * Coolify's `errors` map as one sentence, or null when there is nothing in it.
         *
         * `{"ports_exposes":["The ports exposes field format is invalid."]}` becomes
         * `ports_exposes: The ports exposes field format is invalid.` — the field name is the
         * half that says what to fix, so it is kept.
         */
        fun describeErrors(errors: JsonObject?): String? {
            val map = errors ?: return null

            val lines = map.fieldNames().mapNotNull { field ->
                val value = map.getValue(field)

                val text = when (value) {
                    is JsonArray -> value.joinToString(" ") { it?.toString().orEmpty() }
                    null -> ""
                    else -> value.toString()
                }.trim()

                text.takeIf { it.isNotEmpty() }?.let { "$field: $it" }
            }

            return lines.joinToString(" ").takeIf { it.isNotEmpty() }
        }

        private fun port(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..65535 }

        /**
         * Turns what somebody pasted into the base of an API call.
         *
         * People paste the dashboard URL, and half of them paste it with `/api/v1` already on the
         * end because that is what the documentation shows. Both work.
         */
        fun apiUrl(baseUrl: String, path: String): String {
            val trimmed = baseUrl.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')

            return "$trimmed/api/v1$path"
        }
    }
}
