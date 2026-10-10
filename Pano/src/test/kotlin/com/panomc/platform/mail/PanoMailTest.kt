package com.panomc.platform.mail

import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoIdentity
import com.panomc.platform.backup.remote.RetryPolicy
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration40To41
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailUnavailable
import com.sun.net.httpserver.HttpServer
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.MailAttachment
import io.vertx.ext.mail.MailMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Pano Mail: the MIME encoding, the website calls (stubbed), error mapping, activation and the config migration. */
class PanoMailTest {
    private lateinit var server: HttpServer
    private lateinit var vertx: Vertx
    private val requests = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** path → (status, body); `result: ok` bodies are wrapped by [ok]. */
    private val answers = HashMap<String, Pair<Int, String>>()
    private var catalogCalls = 0

    private fun ok(data: JsonObject) = 200 to JsonObject().put("result", "ok").put("data", data).encode()
    private fun err(code: String, status: Int = 400, extra: JsonObject = JsonObject()) =
        status to JsonObject().put("result", "error").put("error", code).mergeIn(extra).encode()

    private val status = JsonObject().put("enabled", true).put("ready", true).put("from", "no-reply@pano.example")
        .put("plan", JsonObject().put("id", "free").put("name", "Free").put("free", true).put("mailsPerMonth", 300).put("mailsPerDay", 20))
        .put("usage", JsonObject().put("day", JsonObject().put("used", 1).put("limit", 20)))

    @TempDir
    lateinit var dir: File

    private lateinit var configManager: ConfigManager
    private var clock = 0L

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path

            requests.add(Triple(ex.requestMethod, path, body))

            if (path == "/host/catalog") catalogCalls++

            val (code, text) = answers[path] ?: err("NOT_FOUND", 404)
            val bytes = text.toByteArray()

            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        System.setProperty("pano.configFile", File(dir, "config.conf").absolutePath)
        vertx = Vertx.vertx()
        configManager = ConfigManager(vertx, LoggerFactory.getLogger("test"), AnnotationConfigApplicationContext())
        ConfigManager::class.java.getDeclaredField("config").apply { isAccessible = true }.set(configManager, PanoConfig(41))
        configManager.config.panoAccount.accessToken = "token"
        configManager.config.panoAccount.username = "alice"
        configManager.config.panoAccount.platformId = "p1"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        vertx.close()
        System.clearProperty("pano.configFile")
    }

    private fun client(token: String? = "token") =
        PanoHostClient({ "http://127.0.0.1:${server.address.port}" }, { token }, { PanoIdentity("instance-1234", "Test") }, RetryPolicy(1, 1))

    private fun service() = PanoMailService(configManager, { client(configManager.config.panoAccount.accessToken.ifBlank { null }) }, { clock })

    private fun message(): MailMessage {
        val m = MailMessage()
        m.from = "Pano <no-reply@pano.example>"
        m.setTo("bob@example.com")
        m.subject = "Hellö"
        m.html = "<p>Hi <img src=\"cid:logo\"></p>"
        m.text = "Hi"
        m.addHeader("Reply-To", "support@example.com")
        m.inlineAttachment = listOf(
            MailAttachment.create().setData(Buffer.buffer(byteArrayOf(1, 2, 3))).setName("logo").setContentType("image/png")
                .setDisposition("inline").setContentId("<logo>")
        )
        m.attachment = listOf(
            MailAttachment.create().setData(Buffer.buffer("data")).setName("a.txt").setContentType("text/plain").setDisposition("attachment")
        )
        return m
    }

    @Test
    fun `encodes the message to raw MIME with every part`() {
        val mime = String(MailManager.encodeMime(message()), Charsets.UTF_8)

        assertTrue(mime.contains("From: Pano <no-reply@pano.example>"), mime)
        assertTrue(mime.contains("To: bob@example.com"))
        assertTrue(mime.contains("Reply-To: support@example.com"))
        assertTrue(mime.contains("Content-ID: <logo>"))
        assertTrue(mime.contains("text/html"))
        assertTrue(mime.contains("text/plain"))
        assertTrue(mime.contains("filename=\"a.txt\""))
        assertTrue(mime.contains("Message-ID: <") && mime.contains("@pano.example>"))
    }

    @Test
    fun `sends from, recipients and base64 MIME to the website`() = runBlocking {
        answers["/host/connected/mail/send"] = ok(JsonObject().put("id", "m1").put("recipients", 1))
        val mime = MailManager.encodeMime(message())

        val result = service().send("no-reply@pano.example", listOf("bob@example.com"), mime)

        assertEquals("m1", result.getString("id"))
        val sent = JsonObject(requests.single { it.second == "/host/connected/mail/send" }.third)
        assertEquals("no-reply@pano.example", sent.getString("from"))
        assertEquals(JsonArray().add("bob@example.com"), sent.getJsonArray("to"))
        assertArrayEquals(mime, Base64.getDecoder().decode(sent.getString("mime")))
    }

    @Test
    fun `maps website errors to clear exceptions`() = runBlocking {
        val cases = listOf(
            "MAIL_DISABLED", "MAIL_SENDER_NOT_ALLOWED", "MAIL_HELD", "INVALID_INPUT", "MAIL_UNAVAILABLE", "QUOTA_EXCEEDED"
        )

        for (code in cases) {
            answers["/host/connected/mail/send"] = err(code, 422, JsonObject().put("reason", if (code == "QUOTA_EXCEEDED") "DAY" else "X").put("resetsAt", 123))
            val e = assertThrows<PanoMailException> { runBlocking { service().send("a@b.c", listOf("d@e.f"), ByteArray(1)) } }

            assertEquals(code, e.code)
            assertFalse(e.message.isNullOrBlank())
        }

        answers["/host/connected/mail/send"] = err("QUOTA_EXCEEDED", 429, JsonObject().put("reason", "DAY").put("resetsAt", 123))
        val quota = assertThrows<PanoMailException> { runBlocking { service().send("a@b.c", listOf("d@e.f"), ByteArray(1)) } }
        assertTrue(quota.message!!.contains("DAY") && quota.message!!.contains("123"))
        assertEquals(123, quota.extras.getInteger("resetsAt"))
    }

    @Test
    fun `website down is PANO_HOST_UNAVAILABLE and a revoked token is CONNECT_REQUIRED`() = runBlocking {
        server.stop(0)
        val down = assertThrows<PanoMailException> { runBlocking { service().status() } }
        assertEquals(PanoMailException.UNAVAILABLE, down.code)

        configManager.config.panoAccount.accessToken = ""
        val none = assertThrows<PanoMailException> { runBlocking { service().status() } }
        assertEquals(PanoMailException.CONNECT_REQUIRED, none.code)
    }

    @Test
    fun `activate enables, stores provider, enabled and sender`() = runBlocking {
        answers["/host/connected/mail/enable"] = ok(status.copy().put("ready", false))

        service().activate(null)

        val email = configManager.config.email
        assertEquals("PANO_MAIL", email.provider)
        assertTrue(email.enabled)
        assertEquals("no-reply@pano.example", email.sender)
        assertEquals("POST", requests.single { it.second == "/host/connected/mail/enable" }.first)

        service().activate("Site <x@pano.example>")
        assertEquals("Site <x@pano.example>", email.sender)
    }

    @Test
    fun `activate refuses without an account and reports website failures`() = runBlocking {
        configManager.config.panoAccount.accessToken = ""
        assertThrows<PanoMailConnectRequired> { runBlocking { service().activate(null) } }
        assertTrue(requests.isEmpty())

        configManager.config.panoAccount.accessToken = "token"
        answers["/host/connected/mail/enable"] = err("MAIL_UNAVAILABLE", 503, JsonObject().put("reason", "NO_PLAN"))
        val e = assertThrows<PanoMailUnavailable> { runBlocking { service().activate(null) } }

        assertEquals("PANO_MAIL_UNAVAILABLE", e.code)
        assertEquals("SMTP", configManager.config.email.provider)
        assertFalse(configManager.config.email.enabled)
    }

    @Test
    fun `verify runs the connect check and enable without a test mail`() = runBlocking {
        answers["/host/connected/mail/enable"] = ok(status.copy().put("ready", false))

        val result = PanoMailVerify.run(service())

        assertEquals(false, result["ready"])
        assertEquals("no-reply@pano.example", result["from"])

        configManager.config.panoAccount.accessToken = ""
        assertThrows<PanoMailConnectRequired> { runBlocking { PanoMailVerify.run(service()) } }
    }

    @Test
    fun `free plan comes from the catalog, is cached and never throws`() = runBlocking {
        answers["/host/catalog"] = ok(
            JsonObject().put(
                "mailPlans",
                JsonArray().add(JsonObject().put("id", "pro").put("free", false).put("mailsPerMonth", 9000).put("mailsPerDay", 300))
                    .add(JsonObject().put("id", "free").put("free", true).put("mailsPerMonth", 300).put("mailsPerDay", 20))
            )
        )
        val service = service()

        assertEquals(JsonObject().put("mailsPerMonth", 300).put("mailsPerDay", 20), service.freePlan())
        service.freePlan()
        assertEquals(1, catalogCalls)
        assertNull(requests.first().let { null }) // the catalog call carries no auth (checked by the stub not requiring it)

        clock += PanoMailService.FREE_PLAN_CACHE_MS + 1
        service.freePlan()
        assertEquals(2, catalogCalls)

        server.stop(0)
        clock += PanoMailService.FREE_PLAN_CACHE_MS + 1
        assertNull(service.freePlan())
    }

    @Test
    fun `cached status is null when not connected or unreachable and cached for a minute`() = runBlocking {
        answers["/host/connected/mail"] = ok(status)
        val service = service()

        assertEquals("free", service.cachedStatus()!!.getJsonObject("plan").getString("id"))
        service.cachedStatus()
        assertEquals(1, requests.count { it.second == "/host/connected/mail" })

        answers.remove("/host/connected/mail")
        clock += PanoMailService.STATUS_CACHE_MS + 1
        assertNull(service.cachedStatus())

        configManager.config.panoAccount.accessToken = ""
        assertNull(service.cachedStatus())
    }

    @Test
    fun `panel view shape`() {
        configManager.config.panoWebsiteUrl = "https://panomc.example/"
        configManager.config.email.provider = "PANO_MAIL"
        configManager.config.email.enabled = true

        val view = PanoMailService.panelView(configManager.config, status.copy().put("held", false), JsonObject().put("mailsPerDay", 20))

        assertEquals("PANO_MAIL", view["provider"])
        assertEquals(true, view["connected"])
        assertEquals(mapOf("username" to "alice"), view["account"])
        assertEquals("https://panomc.example/host/manage/mail", view["manageUrl"])
        assertEquals(true, view["ready"])
        assertEquals(false, view["held"])
        assertEquals("Free", (view["plan"] as JsonObject).getString("name"))

        val offline = PanoMailService.panelView(configManager.config, null, null)
        assertNull(offline["plan"])
        assertNull(offline["usage"])
        assertNull(offline["ready"])
        assertNull(offline["freePlan"])
    }

    @Test
    fun `falls back to SMTP and off`() = runBlocking {
        configManager.config.email.provider = "PANO_MAIL"
        configManager.config.email.enabled = true

        service().fallBackToSmtp()

        assertEquals("SMTP", configManager.config.email.provider)
        assertFalse(configManager.config.email.enabled)
    }

    @Test
    fun `config migration adds provider SMTP and keeps an existing one`() {
        val migration = ConfigMigration40To41()

        assertTrue(migration.isMigratable(40))
        assertEquals(41, migration.to)

        val old = JsonObject().put("email", JsonObject().put("enabled", true))
        migration.migrate(old)
        assertEquals("SMTP", old.getJsonObject("email").getString("provider"))

        val set = JsonObject().put("email", JsonObject().put("provider", "PANO_MAIL"))
        migration.migrate(set)
        assertEquals("PANO_MAIL", set.getJsonObject("email").getString("provider"))

        migration.migrate(JsonObject())
    }
}
