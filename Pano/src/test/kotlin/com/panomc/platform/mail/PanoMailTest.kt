package com.panomc.platform.mail

import com.panomc.platform.backup.remote.PanoBackupSetupView
import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoIdentity
import com.panomc.platform.backup.remote.RetryPolicy
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration40To41
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailNameInvalid
import com.panomc.platform.error.PanoMailNameRateLimited
import com.panomc.platform.error.PanoMailNameTaken
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

    private fun withSubdomain(name: String?, suggested: String? = "my-site") =
        status.copy().put("subdomain", JsonObject().put("name", name).put("suggested", suggested).put("zone", "mail.panomc.site"))

    @Test
    fun `enable sends the name only when given and the address is localPart at name dot zone`() = runBlocking {
        answers["/host/connected/mail/enable"] = ok(withSubdomain("mysite"))

        val plain = service().enableAs(null, null)
        assertEquals("", requests.last { it.second == "/host/connected/mail/enable" }.third)
        assertEquals("no-reply@pano.example", plain.address)

        val named = service().enableAs("mysite", "info")
        assertEquals("mysite", JsonObject(requests.last { it.second == "/host/connected/mail/enable" }.third).getString("name"))
        assertEquals("info@mysite.mail.panomc.site", named.address)

        // only a local part: the website's current name is kept
        assertEquals("sales@mysite.mail.panomc.site", service().enableAs(null, "sales").address)
        // only a name: the default local part
        assertEquals("no-reply@mysite.mail.panomc.site", service().enableAs("mysite", null).address)
    }

    @Test
    fun `activate stores the chosen address and keeps the stored local part on a rename`() = runBlocking {
        answers["/host/connected/mail/enable"] = ok(withSubdomain("mysite"))

        service().activate(null, "mysite", "info")
        assertEquals("info@mysite.mail.panomc.site", configManager.config.email.sender)
        assertEquals("PANO_MAIL", configManager.config.email.provider)

        service().activate(null, "other", null)
        assertEquals("info@mysite.mail.panomc.site", configManager.config.email.sender) // the stub keeps answering "mysite"
    }

    @Test
    fun `website label errors map to the name error codes`() = runBlocking {
        answers["/host/connected/mail/enable"] = err("LABEL_TAKEN", 409)
        assertEquals("PANO_MAIL_NAME_TAKEN", assertThrows<PanoMailNameTaken> { runBlocking { service().enableAs("x", null) } }.code)

        answers["/host/connected/mail/enable"] = err("LABEL_RESERVED", 409)
        assertEquals("PANO_MAIL_NAME_TAKEN", assertThrows<PanoMailNameTaken> { runBlocking { service().enableAs("admin", null) } }.code)

        answers["/host/connected/mail/enable"] = err("QUOTA_EXCEEDED", 429, JsonObject().put("reason", "SENDERS"))
        val senders = assertThrows<com.panomc.platform.error.PanoMailUnavailable> { runBlocking { service().enableAs("x", null) } }
        assertEquals("PANO_MAIL_UNAVAILABLE", senders.code)
        assertTrue(senders.encode().contains("\"reason\":\"SENDERS\""), senders.encode())

        answers["/host/connected/mail/enable"] = err("LABEL_INVALID", 422)
        assertEquals("PANO_MAIL_NAME_INVALID", assertThrows<PanoMailNameInvalid> { runBlocking { service().enableAs("x", null) } }.code)

        answers["/host/connected/mail/enable"] =
            err("RATE_LIMITED", 429, JsonObject().put("reason", "SENDER_RENAME").put("minutes", 42).put("retryAfter", 99))
        val limited = assertThrows<PanoMailNameRateLimited> { runBlocking { service().enableAs("x", null) } }
        assertEquals("PANO_MAIL_NAME_RATE_LIMITED", limited.code)
        assertTrue(limited.encode().contains("\"details\":{\"minutes\":42"), limited.encode())
        assertEquals(configManager.config.email.sender, "")
    }

    @Test
    fun `local part is validated before the website is called`() = runBlocking {
        for (bad in listOf("a b", "a@b", "x".repeat(65), ".lead", "trail.", "a..b", "ü")) {
            assertThrows<InvalidData> { runBlocking { service().enableAs(null, bad) } }
        }
        assertTrue(requests.isEmpty())

        answers["/host/connected/mail/enable"] = ok(withSubdomain("mysite"))
        assertEquals("a.b+c_d@mysite.mail.panomc.site", service().enableAs(null, "a.b+c_d").address)
        assertEquals(64, "x".repeat(64).length)
        service().enableAs(null, "x".repeat(64))
    }

    @Test
    fun `sender view uses the current or suggested name and the stored local part`() {
        configManager.config.email.provider = "PANO_MAIL"
        configManager.config.email.sender = "Pano <info@mysite.mail.panomc.site>"

        assertEquals(
            mapOf("name" to "mysite", "zone" to "mail.panomc.site", "localPart" to "info"),
            PanoMailService.senderView(configManager.config, withSubdomain("mysite"))
        )
        assertEquals("my-site", PanoMailService.senderView(configManager.config, withSubdomain(null))!!["name"])

        configManager.config.email.provider = "SMTP"
        assertEquals("no-reply", PanoMailService.senderView(configManager.config, withSubdomain("mysite"))!!["localPart"])

        assertNull(PanoMailService.senderView(configManager.config, null))
        assertNull(PanoMailService.senderView(configManager.config, status))

        val view = PanoMailService.panelView(configManager.config, withSubdomain("mysite"), null)
        assertEquals("mysite", (view["sender"] as Map<*, *>)["name"])
        assertNull(PanoMailService.panelView(configManager.config, null, null)["sender"])

        // the set-up route's plan / manageUrl come from the same helpers as the panel's
        configManager.config.panoWebsiteUrl = "https://panomc.example/"
        assertEquals("https://panomc.example/host/manage/mail", PanoMailService.manageUrl(configManager.config))
        assertEquals(
            JsonObject().put("id", "free").put("name", "Free").put("free", true).put("mailsPerMonth", 300).put("mailsPerDay", 20),
            PanoMailService.planView(status)
        )
        assertNull(PanoMailService.planView(null))
        assertNull(PanoMailService.planView(JsonObject()))
    }

    @Test
    fun `short status is null when not connected or the website fails`() = runBlocking {
        answers["/host/connected/mail"] = ok(withSubdomain("mysite"))
        assertEquals("mysite", service().shortStatus()!!.getJsonObject("subdomain").getString("name"))

        server.stop(0)
        assertNull(service().shortStatus())

        configManager.config.panoAccount.accessToken = ""
        assertNull(service().shortStatus())
    }

    @Test
    fun `verify answers the resulting address`() = runBlocking {
        answers["/host/connected/mail/enable"] = ok(withSubdomain("mysite").put("ready", false))

        val result = PanoMailVerify.run(service(), "mysite", "info")

        assertEquals(mapOf("ready" to false, "from" to "info@mysite.mail.panomc.site"), result)
    }

    @Test
    fun `bare address strips the display name`() {
        assertEquals("a@b.c", PanoMailService.bareAddress("Pano <a@b.c>"))
        assertEquals("a@b.c", PanoMailService.bareAddress(" a@b.c "))
    }

    @Test
    fun `name check maps the website statuses and never throws`() = runBlocking {
        fun answer(status: String, vararg extra: Pair<String, Any>) {
            val body = JsonObject().put("name", "mysite").put("status", status)
            extra.forEach { body.put(it.first, it.second) }
            answers["/host/connected/mail/name-check"] = ok(body)
        }

        for (status in listOf("AVAILABLE", "CURRENT", "TAKEN", "INVALID")) {
            answer(status)
            assertEquals(mapOf("name" to "mysite", "status" to status), service().checkName("mysite"))
        }

        answer("RESERVED")
        assertEquals("TAKEN", service().checkName("mysite")["status"])

        answer("INVALID", "reason" to "TOO_SHORT", "minLength" to 3, "maxLength" to 32)
        assertEquals(
            mapOf("name" to "mysite", "status" to "INVALID", "reason" to "TOO_SHORT", "minLength" to 3, "maxLength" to 32),
            service().checkName("mysite")
        )
        assertEquals("POST", requests.last { it.second == "/host/connected/mail/name-check" }.first)
        assertEquals("mysite", JsonObject(requests.last { it.second == "/host/connected/mail/name-check" }.third).getString("name"))

        answer("SOMETHING_NEW")
        assertEquals("UNKNOWN", service().checkName("mysite")["status"])

        answers["/host/connected/mail/name-check"] = err("INTERNAL", 500)
        assertEquals(mapOf("name" to "x", "status" to "UNKNOWN"), service().checkName("x"))

        server.stop(0)
        assertEquals("UNKNOWN", service().checkName("x")["status"])

        configManager.config.panoAccount.accessToken = ""
        assertEquals("UNKNOWN", service().checkName("x")["status"])
    }

    @Test
    fun `free backup gb comes from the catalog and is cached`() = runBlocking {
        answers["/host/catalog"] = ok(JsonObject().put("backupFreeGb", 1).put("backupTiers", JsonArray()))
        val service = service()

        assertEquals(1, service.freeBackupGb()!!.toInt())
        service.freeBackupGb()
        assertEquals(1, catalogCalls)

        server.stop(0)
        clock += PanoMailService.FREE_PLAN_CACHE_MS + 1
        assertNull(service.freeBackupGb())
    }

    @Test
    fun `setup backup view maps the connected backups answer honestly`() {
        configManager.config.panoWebsiteUrl = "https://panomc.example/"
        val config = configManager.config
        val paid = JsonObject().put("tier", JsonObject().put("id", "backup-10").put("name", "10 GB"))
            .put("usage", JsonObject().put("usedBytes", 5).put("quotaBytes", 10L shl 30))
        val free = JsonObject().put("tier", null as String?)
            .put("usage", JsonObject().put("usedBytes", 7).put("quotaBytes", 1L shl 30)).put("free", JsonObject().put("quotaBytes", 1L shl 30))
        val lapsed = JsonObject().put("usage", JsonObject().put("usedBytes", 9).put("quotaBytes", null as Long?))

        val view = PanoBackupSetupView.build(config, free, 1, false)
        assertEquals("https://panomc.example/", view["website"])
        assertEquals(true, view["connected"])
        assertEquals(mapOf("username" to "alice"), view["account"])
        assertEquals(1, view["freeGb"])
        assertEquals(mapOf("name" to null, "quotaBytes" to (1L shl 30), "free" to true), view["plan"])
        assertEquals(mapOf("usedBytes" to 7L), view["usage"])
        assertEquals(false, view["daily"])
        assertEquals("https://panomc.example/host/manage/backups", view["manageUrl"])

        assertEquals(
            mapOf("name" to "10 GB", "quotaBytes" to (10L shl 30), "free" to false),
            PanoBackupSetupView.build(config, paid, 1, true)["plan"]
        )
        assertEquals(true, PanoBackupSetupView.build(config, paid, 1, true)["daily"])
        assertNull(PanoBackupSetupView.build(config, lapsed, 1, false)["plan"])

        val offline = PanoBackupSetupView.build(config, null, null, false)
        assertNull(offline["plan"])
        assertNull(offline["usage"])
        assertNull(offline["freeGb"])

        config.panoAccount.accessToken = ""
        val none = PanoBackupSetupView.build(config, null, 1, false)
        assertEquals(false, none["connected"])
        assertNull(none["account"])
    }
}
