package com.panomc.node.net

import com.panomc.node.NodeVersion
import com.panomc.node.config.NodeConfig
import com.panomc.node.crypto.Aes256GcmUtil
import com.panomc.node.crypto.NodeKeys
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.core.http.UpgradeRejectedException
import io.vertx.core.http.WebSocket
import io.vertx.core.http.WebSocketClient
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.core.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/**
 * The node's one link to Pano: pairing, the socket, and everything that keeps it alive.
 *
 * Shaped after the Minecraft plugin's `PlatformManager`, because the two solve the same problem in
 * the same environment -- one outbound WebSocket through whatever reverse proxies an operator put
 * in front of Pano, with no inbound port on this side at all. The pieces that matter:
 *
 * - Pairing happens once. It sends this node's RSA public key and stores the token and the AES key
 *   that come back, so a restart reconnects without anyone typing a code again.
 * - The reconnect loop is iterative and backs off from three seconds to thirty. A node that has
 *   not been approved yet is refused with `NEED_PERMISSION`, and that is not an error: it keeps
 *   retrying so that an admin pressing Accept in the panel is all it takes to get in.
 * - Liveness is measured from both ends. Pano pings and gives up on a node whose pongs stop coming
 *   (Vert.x answers ping frames at the protocol level itself). That alone is not enough here: a
 *   proxy or tunnel between the two -- ngrok, Cloudflare, a NAT -- can keep the node's half of the
 *   socket open after Pano is gone, and then no close ever arrives and the node would wait on a
 *   dead socket forever. So the node pings too, every [PING_INTERVAL_MILLIS], counts every inbound
 *   frame as a sign of life (a message, Pano's ping, the pong to its own), and after
 *   [LIVENESS_TIMEOUT_MILLIS] without one closes the socket and reconnects (see
 *   [ConnectionLiveness]). The timeout is above Pano's default of seventy-five seconds, so on a
 *   link that is only slow, Pano is the first to give up.
 */
class PlatformConnection(
    private val vertx: Vertx,
    private val logger: NodeLogger,
    private val config: NodeConfig,
    /** How often the node pings Pano; a parameter only so a test can run the watchdog in milliseconds. */
    private val pingIntervalMillis: Long = PING_INTERVAL_MILLIS,
    /** How long a socket may hear nothing from Pano before it is given up. */
    private val livenessTimeoutMillis: Long = LIVENESS_TIMEOUT_MILLIS
) {
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()

    private val connecting = AtomicBoolean(false)

    @Volatile
    private var running = false

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var secretKey: SecretKey? = null

    @Volatile
    private var backoffSeconds = MIN_BACKOFF_SECONDS

    @Volatile
    private var permissionWarned = false

    /**
     * Whether the current stretch without a connection has been reported. The first failure of an
     * outage is a warning; the retries after it, every few seconds up to thirty, only go to the log
     * -- on a Pano Agent a warning is a line in the admin's server console (SM-74).
     */
    @Volatile
    private var outageReported = false

    @Volatile
    private var onConnected: (() -> Unit)? = null

    /** The current socket's liveness watchdog, or -1 when there has never been a socket. */
    @Volatile
    private var livenessTimer = -1L

    private val endpoint: PlatformEndpoint by lazy { PlatformEndpoint.parse(config.platformUrl) }

    private val webSocketClient: WebSocketClient by lazy {
        vertx.createWebSocketClient()
    }

    private val httpClient: HttpClient by lazy {
        vertx.createHttpClient(
            HttpClientOptions()
                .setSsl(endpoint.ssl)
                .setDefaultHost(endpoint.host)
                .setDefaultPort(endpoint.port)
                .setConnectTimeout(PAIR_TIMEOUT_MILLIS.toInt())
        )
    }

    /**
     * Decides, per inbound message name, whether it is handled at all; null lets everything in.
     *
     * Set while the node is uninstalling itself (SM-64): from the moment the uninstall starts, an
     * install, a start or a backup that arrives would be work on files that are about to be deleted.
     */
    @Volatile
    var inboundGate: ((String) -> Boolean)? = null

    fun isConnected() = webSocket != null

    /** Registers what to do with one inbound message name. */
    fun on(event: String, handler: (String) -> Unit) {
        handlers[event] = handler
    }

    /** Runs once every time the socket comes up, after the hello has been sent. */
    fun onConnected(callback: () -> Unit) {
        onConnected = callback
    }

    /**
     * Pairs this node with Pano and stores the result in [config].
     *
     * Blocking, and called from the launcher thread before anything else starts: there is nothing
     * useful the daemon can do until it knows whether it has an identity, and a half-started node
     * that then fails to pair would have to be torn down again.
     */
    fun pair(
        pairingCode: String?,
        bootstrapToken: String?,
        name: String,
        dataPath: String,
        runtime: String = "PROCESS",
        /**
         * The server a Pano Agent is dedicated to, or null for an ordinary node. Informational:
         * whether Pano treats this daemon as an agent is decided by the code it pairs with, never by
         * what the daemon says about itself.
         */
        agentServer: String? = null
    ) {
        if (config.publicKey.isBlank() || config.privateKey.isBlank()) {
            val (publicKey, privateKey) = NodeKeys.generate()

            config.publicKey = publicKey
            config.privateKey = privateKey
        }

        val body = JsonObject()
            .put("name", name)
            .put("publicKey", config.publicKey)
            .put("version", NodeVersion.VERSION)
            .put("protocolVersion", NodeProtocol.VERSION)
            .put("os", com.panomc.node.host.HostPlatform.os)
            .put("arch", com.panomc.node.host.HostPlatform.arch)
            .put("hostname", com.panomc.node.host.HostPlatform.hostname)
            .put("dataPath", dataPath)
            .put("runtime", runtime)

        if (agentServer != null) {
            body.put("agent", true).put("agentServer", agentServer)
        }

        if (!bootstrapToken.isNullOrBlank()) {
            body.put("bootstrapToken", bootstrapToken)
        } else {
            body.put("pairingCode", pairingCode)
        }

        val response = httpClient
            .request(
                RequestOptions()
                    .setMethod(HttpMethod.POST)
                    .setURI("/api/node/connect")
                    .setTimeout(PAIR_TIMEOUT_MILLIS)
            )
            .compose { request ->
                request.putHeader("Content-Type", "application/json")

                request.send(body.encode())
            }
            .compose { received -> received.body().map { received.statusCode() to it } }
            .toCompletionStage()
            .toCompletableFuture()
            .get(PAIR_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS)

        val payload = try {
            JsonObject(response.second.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw IllegalStateException("Pano answered pairing with HTTP ${response.first} and no JSON body.")
        }

        if (payload.getString("result") == "error") {
            throw IllegalStateException("Pano refused the pairing: ${payload.getString("error")}")
        }

        val token = payload.getString("token")
        val wrappedKey = payload.getString("encryptionKey")

        if (token.isNullOrBlank() || wrappedKey.isNullOrBlank()) {
            throw IllegalStateException("Pano's pairing reply carried no token.")
        }

        config.platformUrl = endpoint.baseUrl
        config.token = token
        config.encryptionKey = NodeKeys.unwrapAesKey(config.privateKey, wrappedKey)
        config.name = name

        logger.notice("Paired with Pano at ${endpoint.baseUrl}.")
    }

    /** Starts the reconnect loop. Returns immediately; everything after this is event driven. */
    fun start() {
        running = true

        connect()
    }

    /** Closes the socket and stops reconnecting. */
    fun stop() {
        running = false

        vertx.cancelTimer(livenessTimer)

        webSocket?.close()
        webSocket = null
    }

    /**
     * Sends one event.
     *
     * Safe to call from any thread: a server's reader thread and the metrics timer both end up
     * here, and the encrypt-and-write has to happen on the socket's own context. Best effort by
     * design -- a frame produced while the socket is down is dropped rather than queued, because
     * the interesting state is re-sent on the next hello anyway.
     */
    fun send(event: String, payload: JsonObject) {
        val socket = webSocket ?: return
        val key = secretKey ?: return

        val frame = JsonObject().put("event", event)

        payload.forEach { entry -> frame.put(entry.key, entry.value) }

        val encrypted = try {
            Aes256GcmUtil.encrypt(frame.encode(), key)
        } catch (exception: Exception) {
            logger.warn("Could not encrypt $event: ${exception.message}")

            return
        }

        vertx.runOnContext {
            try {
                if (webSocket === socket) {
                    socket.writeTextMessage(encrypted)
                }
            } catch (exception: Exception) {
                logger.warn("Could not send $event: ${exception.message}")
            }
        }
    }

    /**
     * Answers a request Pano is waiting on.
     *
     * Every reply goes out under one name with the request's [eventId] echoed back, mirroring what
     * the Minecraft plugin does: Pano pairs the two by that id, so a handler never has to know
     * which reply name belongs to which request. A blank id means the request was fire and forget
     * and nothing is waiting, so nothing is sent.
     */
    fun reply(eventId: String?, payload: JsonObject) {
        if (eventId.isNullOrBlank()) {
            return
        }

        send(NodeProtocol.Outbound.FILE_RESULT, payload.copy().put("eventId", eventId))
    }

    private fun connect() {
        if (!running || !connecting.compareAndSet(false, true)) {
            return
        }

        val options = WebSocketConnectOptions()
            .setHost(endpoint.host)
            .setPort(endpoint.port)
            .setSsl(endpoint.ssl)
            .setURI("/api/node/connection")
            .setMethod(HttpMethod.GET)
            .addHeader("Authorization", "Bearer ${config.token}")

        webSocketClient.connect(options).onComplete { result ->
            connecting.set(false)

            if (result.succeeded()) {
                onSocketOpen(result.result())

                return@onComplete
            }

            onConnectFailed(result.cause())
        }
    }

    private fun onSocketOpen(socket: WebSocket) {
        webSocket = socket
        secretKey = Aes256GcmUtil.base64ToSecretKey(config.encryptionKey)
        backoffSeconds = MIN_BACKOFF_SECONDS
        permissionWarned = false
        outageReported = false

        logger.notice("Connected to Pano at ${endpoint.baseUrl}.")

        val liveness = ConnectionLiveness(livenessTimeoutMillis)

        // Armed before any handler is set, so a socket that fails even that early still ends in a
        // reconnect instead of a daemon that waits on it forever.
        val timer = vertx.setPeriodic(pingIntervalMillis) { id -> watch(socket, liveness, id) }

        livenessTimer = timer

        // Every frame, not only messages: on an idle link, Pano's pings and the pongs to ours are all
        // there is, and the text handler below never sees either of them.
        socket.frameHandler { liveness.heard() }

        socket.textMessageHandler { message -> onTextMessage(message) }

        socket.closeHandler {
            vertx.cancelTimer(timer)

            if (release(socket)) {
                outageReported = true

                logger.notice("Lost the connection to Pano; retrying until it is back.")

                scheduleReconnect()
            }
        }

        socket.exceptionHandler { cause ->
            // A socket the watchdog already gave up on fails once more when its close times out.
            // That is the tail of an outage already reported, not a new one.
            if (webSocket === socket) {
                logger.warn("Node socket failed: ${cause.message}")
            } else {
                logger.info("A released socket to Pano failed: ${cause.message}")
            }
        }

        try {
            onConnected?.invoke()
        } catch (exception: Exception) {
            logger.error("Failed to finish the handshake: ${exception.message}", exception)
        }
    }

    private fun onConnectFailed(cause: Throwable?) {
        val error = (cause as? UpgradeRejectedException)?.let { rejection ->
            try {
                rejection.body?.toJsonObject()?.getString("error")
            } catch (_: Exception) {
                null
            }
        }

        when (error) {
            NEED_PERMISSION -> {
                if (!permissionWarned) {
                    permissionWarned = true

                    logger.warn(
                        "This node is waiting to be approved. Accept it in Panel -> Servers -> Nodes; " +
                            "the daemon keeps retrying until you do."
                    )
                }
            }

            INVALID_TOKEN -> report("Pano rejected this node's token. Delete config.conf and pair again with a new code.") {
                logger.error(it)
            }

            else -> report("Could not reach Pano: ${cause?.message ?: "unknown error"}") { logger.warn(it) }
        }

        scheduleReconnect()
    }

    /** Reports [message] through [first] once per outage; later failures of it are logged as info. */
    private fun report(message: String, first: (String) -> Unit) {
        if (outageReported) {
            logger.info(message)

            return
        }

        outageReported = true

        first(message)
    }

    /**
     * One tick of [socket]'s watchdog: ping Pano, or give the socket up when nothing has come back
     * for the whole timeout.
     */
    private fun watch(socket: WebSocket, liveness: ConnectionLiveness, timer: Long) {
        if (webSocket !== socket) {
            vertx.cancelTimer(timer)

            return
        }

        when (liveness.check()) {
            // A socket that refuses the ping is already closed on this side, whatever its close
            // handler has not yet said: waiting out the rest of the timeout would only add a minute
            // to the outage.
            ConnectionLiveness.Verdict.PING -> try {
                socket.writePing(Buffer.buffer()).onFailure { cause ->
                    vertx.runOnContext { giveUp(socket, timer, "Could not ping Pano (${cause.message}); reconnecting.") }
                }
            } catch (exception: Exception) {
                giveUp(socket, timer, "Could not ping Pano (${exception.message}); reconnecting.")
            }

            ConnectionLiveness.Verdict.GIVE_UP ->
                giveUp(socket, timer, "No answer from Pano for ${liveness.silenceMillis() / 1000}s; reconnecting.")
        }
    }

    /** Gives [socket] up and reconnects, once, whichever of the watchdog's reasons got here first. */
    private fun giveUp(socket: WebSocket, timer: Long, reason: String) {
        vertx.cancelTimer(timer)

        if (!release(socket)) {
            return
        }

        outageReported = true

        logger.warn(reason)

        // The reconnect does not wait for the close. On a dead tunnel the close frame is never
        // answered and Vert.x drops the connection only after its closing timeout; the close
        // handler that runs then finds the socket already released and does nothing.
        scheduleReconnect()

        socket.close()
    }

    /**
     * Lets go of [socket] if it is still the current one, and says whether this call was the one
     * that did.
     *
     * The close handler and the watchdog can both decide a socket is finished -- the watchdog
     * closing a socket is exactly what makes its close handler run -- and only the one that wins
     * here may schedule the reconnect. Two timers would mean two sockets: [connect] only refuses
     * to overlap an attempt that is still in flight, not one that has already succeeded.
     */
    @Synchronized
    private fun release(socket: WebSocket): Boolean {
        if (webSocket !== socket) {
            return false
        }

        webSocket = null
        secretKey = null

        return true
    }

    private fun scheduleReconnect() {
        if (!running) {
            return
        }

        val delay = backoffSeconds

        backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)

        vertx.setTimer(delay * 1000L) { connect() }
    }

    private fun onTextMessage(encrypted: String) {
        val key = secretKey ?: return

        val text = try {
            Aes256GcmUtil.decrypt(encrypted, key)
        } catch (exception: Exception) {
            logger.warn("Dropped an unreadable frame (${exception.javaClass.simpleName}).")

            return
        }

        val event = try {
            JsonObject(text).getString("event")
        } catch (exception: Exception) {
            logger.warn("Dropped a malformed frame: ${exception.message}")

            return
        }

        if (inboundGate?.invoke(event) == false) {
            logger.warn("Refusing \"$event\": this node is being removed from Pano.")

            return
        }

        val handler = handlers[event]

        if (handler == null) {
            logger.warn("Ignoring unknown message \"$event\" from Pano.")

            return
        }

        // Handlers do disk and process work, so none of them may run on the event loop that owns
        // this socket: a thirty-second graceful stop there would freeze every other server's
        // console and the heartbeat with it.
        //
        // Throwable, not Exception: a handler that dies of a NoClassDefFoundError -- the jar was
        // rewritten under the running JVM, say -- would otherwise fail into a future nobody reads,
        // and the daemon would go on receiving frames and answering none of them without a word.
        vertx.executeBlocking<Void>({
            try {
                handler(text)
            } catch (throwable: Throwable) {
                logger.error("Handling $event failed: ${throwable.message}", throwable)
            }

            null
        }, false)
    }

    companion object {
        const val NEED_PERMISSION = "NEED_PERMISSION"
        const val INVALID_TOKEN = "INVALID_TOKEN"

        const val MIN_BACKOFF_SECONDS = 3L
        const val MAX_BACKOFF_SECONDS = 30L

        /**
         * How often the node pings Pano: about as often as Pano pings it, and far inside the idle
         * timeouts of the proxies in between (Cloudflare's is a hundred seconds).
         */
        const val PING_INTERVAL_MILLIS = 30_000L

        /**
         * How long a socket may hear nothing before the node gives it up: three unanswered pings,
         * and above the seventy-five seconds Pano allows a node by default, so Pano is the first
         * to judge a slow link.
         */
        const val LIVENESS_TIMEOUT_MILLIS = 90_000L

        private const val PAIR_TIMEOUT_MILLIS = 15_000L
    }
}
