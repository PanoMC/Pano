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

        val changed = HostedEnvConfig(hosted).apply(config)
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
