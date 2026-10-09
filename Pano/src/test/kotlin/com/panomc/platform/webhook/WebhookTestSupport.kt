package com.panomc.platform.webhook

import com.google.gson.GsonBuilder
import com.panomc.platform.db.DBEntity
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.util.SecretCipher
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.LenientListStringAdapterFactory
import io.vertx.core.Vertx
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A resolver with a fixed table. A name that is not in it fails like an unknown host. [lookups] counts the questions. */
class StubResolver(vararg entries: Pair<String, List<String>>) : HostResolver {
    private val table = ConcurrentHashMap<String, List<InetAddress>>()
    val lookups = AtomicInteger()

    init {
        for ((host, addresses) in entries) set(host, *addresses.toTypedArray())
    }

    fun set(host: String, vararg addresses: String) {
        table[host] = addresses.map { InetAddress.getByName(it) }
    }

    override suspend fun resolve(host: String): List<InetAddress> {
        lookups.incrementAndGet()

        return table[host] ?: throw java.net.UnknownHostException(host)
    }
}

object WebhookTestSupport {
    /** A deterministic cipher for tests; the key is never used for anything else. */
    fun cipher(): SecretCipher = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { (it * 7 + 1).toByte() })

    fun outbound(
        vertx: Vertx, resolver: HostResolver, totalTimeoutMs: Long = 5_000L,
        customize: (io.vertx.ext.web.client.WebClientOptions) -> Unit = {}
    ): OutboundHttp =
        OutboundHttp.create(vertx, version = "test", resolver = resolver, totalTimeoutMs = totalTimeoutMs, customize = customize)

    /** The same Gson the platform registers for rows (`SpringConfig.gson`). */
    fun installGson() {
        DBEntity.gson = GsonBuilder()
            .registerTypeAdapterFactory(LenientListStringAdapterFactory())
            .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
            .registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
            .create()
    }

    /** A claimed-looking row (status SENDING, attempt 1) for the sender tests. */
    fun row(
        url: String,
        body: String = """{"id":"e1","event":"market.order.paid"}""",
        signing: WebhookSigning = WebhookSigning.NONE,
        secret: String? = null,
        format: WebhookFormat = WebhookFormat.JSON,
        attempts: Int = 1,
        id: Long = 11,
        eventId: String = "00000000-0000-4000-8000-000000000001",
        event: String = "market.order.paid",
        endpointId: Long = 1
    ) = WebhookDelivery(
        id = id, endpointId = endpointId, source = WebhookEvents.sourceOf(event), eventId = eventId, event = event, url = url,
        format = format, signing = signing, secret = secret, body = body, status = WebhookDeliveryStatus.SENDING,
        attempts = attempts, maxAttempts = 8
    )
}
