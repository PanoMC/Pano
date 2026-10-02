package com.panomc.platform.hosted

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.HoconWriter
import com.panomc.platform.config.PanoConfig
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File

/**
 * [HostedEnvConfig] against real HOCON files: a config.conf is written to a temp dir, parsed the way
 * Vert.x's hocon store does, changed by the env and rendered back with [HoconWriter].
 */
class HostedEnvConfigTest {
    @TempDir
    lateinit var dir: File

    private val hosted = mapOf(
        "PANO_HOSTED" to "pano-host",
        "PANO_HOST_WORKLOAD_ID" to "wl_1",
        "PANO_HOST_INSTANCE_SECRET" to "instance-secret",
        "PANO_DB_HOST" to "pw-wl_1-db",
        "PANO_DB_PORT" to "3306",
        "PANO_DB_NAME" to "pano",
        "PANO_DB_USER" to "pano",
        "PANO_DB_PASSWORD" to "db-secret",
        "PANO_SMTP_HOST" to "smtp.panomc.com",
        "PANO_SMTP_PORT" to "587",
        "PANO_SMTP_USER" to "wl_1",
        "PANO_SMTP_PASSWORD" to "smtp-secret"
    )

    /** A 33-version config.conf as a user would have it: every section present, bare-metal values. */
    private fun writeConf(step: Int = 5): File = File(dir, "config.conf").apply {
        writeText(
            """
            config-version = 33
            website-name = "Test"
            setup { step = $step }
            database {
              type = "mariadb"
              host = "127.0.0.1:3306"
              name = "old"
              username = "root"
              password = "old-password"
              prefix = "pano_"
            }
            email {
              enabled = false
              sender = "Pano <no-reply@example.com>"
              hostname = ""
              port = 465
              username = ""
              password = ""
              ssl = true
              starttls = ""
              authMethods = ""
            }
            server {
              host = "0.0.0.0"
              http-port = 80
              https-port = 443
              ssl-mode = "DISABLED"
              redirect-https = false
              ui-max-memory-mb = 200
              trusted-proxies = ["203.0.113.7"]
            }
            """.trimIndent()
        )
    }

    private fun parse(file: File): JsonObject =
        JsonObject(ConfigFactory.parseFile(file).root().render(ConfigRenderOptions.concise()))

    private fun load(file: File) = PanoConfig.from(parse(file))

    private fun save(config: PanoConfig, file: File) =
        file.writeText(HoconWriter.render(JsonObject(config.toString()), PanoConfig::class.java))

    @Test
    fun `hosted env rewrites db, smtp, port and proxies and survives a HOCON round trip`() {
        val file = writeConf()
        val config = load(file)

        // First boot (a new config): mail is turned on along with the relay settings.
        val changed = HostedEnvConfig(hosted).apply(config, creatingConfig = true)
        save(config, file)
        val reloaded = parse(file)

        assertTrue(
            changed.containsAll(
                listOf(
                    "database.host", "database.name", "database.username", "database.password",
                    "email.enabled", "email.hostname", "email.port", "email.ssl", "email.starttls",
                    "server.http-port", "server.trusted-proxies"
                )
            )
        )
        assertFalse(changed.contains("database.type"))
        changed.forEach { assertFalse(it.contains("secret")) }

        val db = reloaded.getJsonObject("database")
        assertEquals("pw-wl_1-db:3306", db.getString("host"))
        assertEquals("pano", db.getString("name"))
        assertEquals("pano", db.getString("username"))
        assertEquals("db-secret", db.getString("password"))
        assertEquals("pano_", db.getString("prefix"))

        val mail = reloaded.getJsonObject("email")
        assertTrue(mail.getBoolean("enabled"))
        assertEquals("smtp.panomc.com", mail.getString("hostname"))
        assertEquals(587, mail.getInteger("port"))
        assertEquals("wl_1", mail.getString("username"))
        assertEquals("smtp-secret", mail.getString("password"))
        assertFalse(mail.getBoolean("ssl"))
        // Hosted: the Portal relay has no STARTTLS, so 587 must not demand it.
        assertEquals("OPTIONAL", mail.getString("starttls"))
        assertEquals("Pano <no-reply@example.com>", mail.getString("sender"))

        val server = reloaded.getJsonObject("server")
        assertEquals(8088, server.getInteger("http-port"))
        val proxies = server.getJsonArray("trusted-proxies").list
        assertEquals("203.0.113.7", proxies.first())
        assertTrue(proxies.containsAll(HostedEnvConfig.PRIVATE_PROXY_RANGES))

        // Second boot with the same env: nothing to change.
        assertEquals(emptyList<String>(), HostedEnvConfig(hosted).apply(load(file)))
    }

    @Test
    fun `db password rotation is picked up on the next hosted boot`() {
        val file = writeConf()
        HostedEnvConfig(hosted).let { env -> load(file).also { env.apply(it); save(it, file) } }

        val rotated = HostedEnvConfig(hosted + ("PANO_DB_PASSWORD" to "rotated"))
        assertTrue(rotated.shouldApply(creatingConfig = false))
        val config = load(file)
        assertEquals(listOf("database.password"), rotated.apply(config))
        save(config, file)

        assertEquals("rotated", parse(file).getJsonObject("database").getString("password"))
    }

    @Test
    fun `own mail settings saved in the panel survive hosted boots until host mail is chosen again`() {
        val file = writeConf()
        val env = HostedEnvConfig(hosted)

        load(file).also { env.apply(it); save(it, file) }

        // The customer switches to another provider in the panel (host-managed = false).
        load(file).also {
            it.email.hostManaged = false
            it.email.hostname = "smtp.sendgrid.net"
            it.email.port = 587
            it.email.username = "apikey"
            save(it, file)
        }

        val booted = load(file)
        assertFalse(env.apply(booted).any { it.startsWith("email.") })
        assertEquals("smtp.sendgrid.net", booted.email.hostname)

        // Back to Pano Host mail: the relay values return.
        booted.email.hostManaged = true
        assertTrue(env.apply(booted).contains("email.hostname"))
        assertNotEquals("smtp.sendgrid.net", booted.email.hostname)
    }

    @Test
    fun `hosted boots keep mail turned off and the customer's sender, a new config starts with mail on`() {
        val file = writeConf()
        val env = HostedEnvConfig(hosted + ("PANO_SMTP_FROM" to "noreply@shop.panomc.site"))

        load(file).also { env.apply(it, creatingConfig = true); assertTrue(it.email.enabled); save(it, file) }

        load(file).also {
            it.email.enabled = false
            it.email.hostSender = "support@shop.panomc.site"
            save(it, file)
        }

        val booted = load(file)
        env.apply(booted)

        assertFalse(booted.email.enabled)
        assertEquals("support@shop.panomc.site", booted.email.sender)

        booted.email.hostSender = null
        env.apply(booted)
        assertEquals("noreply@shop.panomc.site", booted.email.sender)
    }

    @Test
    fun `migration 36 to 37 keeps a copy of own SMTP settings only`() {
        val own = JsonObject().put(
            "email",
            JsonObject().put("host-managed", false).put("hostname", "smtp.sendgrid.net").put("port", 587)
                .put("username", "apikey").put("password", "pw").put("sender", "a@b.c")
        )

        com.panomc.platform.config.migration.ConfigMigration36To37().migrate(own)
        val custom = own.getJsonObject("email").getJsonObject("custom")
        assertEquals("smtp.sendgrid.net", custom.getString("hostname"))
        assertEquals("pw", custom.getString("password"))
        assertEquals(587, custom.getInteger("port"))

        val host = JsonObject().put("email", JsonObject().put("host-managed", true).put("hostname", "relay"))
        com.panomc.platform.config.migration.ConfigMigration36To37().migrate(host)
        assertFalse(host.getJsonObject("email").containsKey("custom"))
    }

    @Test
    fun `migration 35 to 36 keeps existing mail blocks on host mail`() {
        val config = JsonObject().put("email", JsonObject().put("hostname", "x"))

        com.panomc.platform.config.migration.ConfigMigration35To36().migrate(config)
        assertEquals(true, config.getJsonObject("email").getBoolean("host-managed"))

        val off = JsonObject().put("email", JsonObject().put("host-managed", false))
        com.panomc.platform.config.migration.ConfigMigration35To36().migrate(off)
        assertEquals(false, off.getJsonObject("email").getBoolean("host-managed"))
    }

    @Test
    fun `non-hosted env only seeds a new config`() {
        val env = HostedEnvConfig(
            mapOf("PANO_CONTAINER" to "1", "PANO_DB_HOST" to "db", "PANO_DB_NAME" to "pano", "PANO_DB_USER" to "u")
        )

        assertFalse(env.isHosted)
        assertTrue(env.isContainer)
        assertTrue(env.shouldApply(creatingConfig = true))
        assertFalse(env.shouldApply(creatingConfig = false))
        assertEquals("db:3306", env.database!!.hostWithPort)
        assertEquals("", env.database!!.password)
        assertEquals(8088, env.httpPort)

        val config = load(writeConf())
        env.apply(config)
        assertEquals(listOf("203.0.113.7"), config.server.trustedProxies)
    }

    @Test
    fun `host mail view matches what apply writes and only carries the password on request`() {
        val view = HostedEnvConfig(hosted).hostMail("old@x.com")!!

        assertEquals("smtp.panomc.com", view["hostname"])
        assertEquals(587, view["port"])
        assertEquals(false, view["ssl"])
        assertEquals("OPTIONAL", view["starttls"])
        assertEquals("wl_1", view["username"])
        assertEquals("old@x.com", view["sender"])
        assertFalse(view.containsKey("password"))
        assertEquals("smtp-secret", HostedEnvConfig(hosted).hostMail("", withPassword = true)!!["password"])

        assertNull(HostedEnvConfig(hosted - "PANO_HOSTED").hostMail(""))
        assertNull(HostedEnvConfig(hosted - "PANO_SMTP_HOST").hostMail(""))
    }

    @Test
    fun `host sender keeps the default's domain and only takes a name`() {
        val env = HostedEnvConfig(hosted + ("PANO_SMTP_FROM" to "noreply@shop.panomc.site"))

        assertEquals("destek@shop.panomc.site", env.hostSender("destek"))
        assertEquals("destek.ekip@shop.panomc.site", env.hostSender(" destek.ekip@SHOP.panomc.site "))
        assertNull(env.hostSender(""))
        assertNull(env.hostSender("noreply@shop.panomc.site"))
        assertNull(env.hostSender("noreply"))

        assertThrows(IllegalArgumentException::class.java) { env.hostSender("admin@mail.kabuk.app") }
        assertThrows(IllegalArgumentException::class.java) { env.hostSender("bad name") }
        assertThrows(IllegalArgumentException::class.java) { env.hostSender("a..b") }
        assertThrows(IllegalArgumentException::class.java) { env.hostSender("x".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { HostedEnvConfig(hosted).hostSender("destek") }
    }

    @Test
    fun `the DB pool stays below the DB user's connection limit`() {
        assertEquals(100, HostedEnvConfig(emptyMap()).dbPoolSize, "self-hosted without a limit")
        assertEquals(32, HostedEnvConfig(hosted).dbPoolSize, "Pano Host's default limit of 40")
        assertEquals(16, HostedEnvConfig(hosted + ("PANO_DB_MAX_CONNECTIONS" to "20")).dbPoolSize)
        assertEquals(8, HostedEnvConfig(mapOf("PANO_DB_MAX_CONNECTIONS" to "10")).dbPoolSize, "a limit outside Pano Host too")
        assertEquals(1, HostedEnvConfig(mapOf("PANO_DB_MAX_CONNECTIONS" to "2")).dbPoolSize)
        assertEquals(100, HostedEnvConfig(mapOf("PANO_DB_MAX_CONNECTIONS" to "5000")).dbPoolSize, "never above the default")
        assertEquals(32, HostedEnvConfig(hosted + ("PANO_DB_MAX_CONNECTIONS" to "nope")).dbPoolSize)
    }

    @Test
    fun `manage url points at the website instance page`() {
        assertEquals("https://panomc.com/host/manage/instances/wl_1", HostedEnvConfig(hosted).manageUrl)
        assertEquals(
            "https://panomc.com/host/manage/instances",
            HostedEnvConfig(hosted - "PANO_HOST_WORKLOAD_ID").manageUrl
        )
        assertEquals(
            "https://dev.panomc.com/host/manage/instances/x",
            HostedEnvConfig(hosted + ("PANO_HOST_MANAGE_URL" to "https://dev.panomc.com/host/manage/instances/x")).manageUrl
        )
        assertNull(HostedEnvConfig(emptyMap()).manageUrl)
    }

    @Test
    fun `no env means nothing to apply`() {
        val env = HostedEnvConfig(emptyMap())
        assertFalse(env.hasAny)
        assertFalse(env.shouldApply(creatingConfig = true))
        assertNull(env.httpPort)
        assertNull(env.database)
        assertNull(env.smtp)
    }

    @Test
    fun `explicit and invalid ports, smtp tls modes and incomplete db`() {
        val base = mapOf("PANO_HOSTED" to "pano-host", "PANO_HTTP_PORT" to "18088", "PANO_SMTP_HOST" to "smtp")

        assertEquals(18088, HostedEnvConfig(base).httpPort)
        assertEquals(8088, HostedEnvConfig(base + ("PANO_HTTP_PORT" to "nope")).httpPort)
        assertEquals(587, HostedEnvConfig(base).smtp!!.port)
        assertEquals(3306, HostedEnvConfig(base + mapOf("PANO_DB_HOST" to "d", "PANO_DB_NAME" to "n", "PANO_DB_USER" to "u", "PANO_DB_PORT" to "0")).database!!.port)
        assertNull(HostedEnvConfig(base + ("PANO_DB_HOST" to "d")).database)

        val file = writeConf()
        val ssl = load(file)
        HostedEnvConfig(base + ("PANO_SMTP_PORT" to "465")).apply(ssl)
        assertTrue(ssl.email.ssl)
        assertEquals("DISABLED", ssl.email.starttls)

        val plain = load(file)
        HostedEnvConfig(base + ("PANO_SMTP_PORT" to "25")).apply(plain)
        assertFalse(plain.email.ssl)
        assertEquals("OPTIONAL", plain.email.starttls)

        val relay = load(file)
        HostedEnvConfig(base + ("PANO_SMTP_PORT" to "2525")).apply(relay)
        assertFalse(relay.email.ssl)
        assertEquals("OPTIONAL", relay.email.starttls)
    }

    @Test
    fun `starttls is required on 587 only outside Pano Host and can be overridden`() {
        val file = writeConf()

        val hostedSubmission = load(file)
        HostedEnvConfig(mapOf("PANO_HOSTED" to "pano-host", "PANO_SMTP_HOST" to "relay", "PANO_SMTP_PORT" to "587")).apply(hostedSubmission)
        assertEquals("OPTIONAL", hostedSubmission.email.starttls)

        val selfRun = load(file)
        HostedEnvConfig(mapOf("PANO_CONTAINER" to "1", "PANO_SMTP_HOST" to "smtp.example.com", "PANO_SMTP_PORT" to "587")).apply(selfRun)
        assertFalse(selfRun.email.ssl)
        assertEquals("REQUIRED", selfRun.email.starttls)

        val forced = load(file)
        HostedEnvConfig(mapOf("PANO_HOSTED" to "pano-host", "PANO_SMTP_HOST" to "relay", "PANO_SMTP_PORT" to "2525", "PANO_SMTP_STARTTLS" to "required")).apply(forced)
        assertEquals("REQUIRED", forced.email.starttls)

        val disabled = load(file)
        HostedEnvConfig(mapOf("PANO_CONTAINER" to "1", "PANO_SMTP_HOST" to "smtp", "PANO_SMTP_STARTTLS" to "disabled")).apply(disabled)
        assertEquals("DISABLED", disabled.email.starttls)

        val bogus = load(file)
        HostedEnvConfig(mapOf("PANO_CONTAINER" to "1", "PANO_SMTP_HOST" to "smtp", "PANO_SMTP_STARTTLS" to "sometimes")).apply(bogus)
        assertEquals("REQUIRED", bogus.email.starttls)
    }

    @Test
    fun `secrets are masked in toString`() {
        val env = HostedEnvConfig(hosted)
        assertFalse(env.database.toString().contains("db-secret"))
        assertFalse(env.smtp.toString().contains("smtp-secret"))
    }

    @Test
    fun `ConfigManager applies the hosted env when loading an existing config file`() = runBlocking {
        val file = writeConf()
        val previous = System.getProperty("pano.configFile")
        System.setProperty("pano.configFile", file.absolutePath)
        val vertx = Vertx.vertx()
        val context = AnnotationConfigApplicationContext().apply { refresh() }

        try {
            val manager = ConfigManager(vertx, LoggerFactory.getLogger("test"), context)
            manager.envConfig = HostedEnvConfig(hosted)
            manager.init()

            assertEquals("pw-wl_1-db:3306", manager.config.database.host)
            assertEquals(8088, manager.config.server.httpPort)

            val onDisk = parse(file)
            assertEquals("db-secret", onDisk.getJsonObject("database").getString("password"))
            assertEquals(8088, onDisk.getJsonObject("server").getInteger("http-port"))
            assertEquals(33, onDisk.getInteger("config-version"))
        } finally {
            vertx.close()
            context.close()
            if (previous == null) System.clearProperty("pano.configFile") else System.setProperty("pano.configFile", previous)
        }
    }
}
