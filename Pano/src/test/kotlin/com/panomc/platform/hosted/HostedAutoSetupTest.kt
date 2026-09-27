package com.panomc.platform.hosted

import com.panomc.platform.PanoApiManager
import com.panomc.platform.ReleaseStage
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.util.UsageMode
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HostedAutoSetupTest {
    private val secret = "inst-secret-0123456789abcdef-XYZ"
    private val adminPassword = "Extra-Admin-Pass-4242"
    private val handoverCode = "5b2d7c1e-2f0a-4c8e-9d61-0f3a8b7e1c55"

    private lateinit var vertx: Vertx
    private lateinit var plane: FakeControlPlane
    private lateinit var webClient: WebClient
    private val logs = CopyOnWriteArrayList<String>()

    private class MemoryStore : HostSsoUserStore {
        data class U(val id: Long, val username: String, val email: String, var admin: Boolean, val password: String)

        val users = mutableListOf<U>()
        val mappings = mutableMapOf<String, Long>()

        fun user(id: Long) = users.single { it.id == id }

        override suspend fun mappedUserId(key: String) = mappings[key]
        override suspend fun setMapping(key: String, userId: Long) { mappings[key] = userId }
        override suspend fun userExists(userId: Long) = users.any { it.id == userId }
        override suspend fun userIdByEmail(email: String) = users.firstOrNull { it.email.equals(email, true) }?.id
        override suspend fun isAdmin(userId: Long) = user(userId).admin
        override suspend fun isBanned(userId: Long) = false
        override suspend fun usernameTaken(username: String) = users.any { it.username.equals(username, true) }
        override suspend fun emailTaken(email: String) = users.any { it.email.equals(email, true) }
        override suspend fun createUser(username: String, email: String, password: String) =
            U(users.size + 1L, username, email, false, password).also { users += it }.id
        override suspend fun grantAdmin(userId: Long) { user(userId).admin = true }
    }

    private inner class MemoryTarget : HostedSetupTarget {
        val config = PanoConfig(1, releaseChannel = ReleaseStage.ALPHA)
        val store = MemoryStore()
        var dbInits = 0
        var installer: Long? = null
        var connection: PanoApiManager.Companion.PlatformConnection? = null
        var connectedApiUrl: String? = null
        var failMarkInstaller = 0

        override fun currentStep() = config.setup.step
        override fun updateConfig(change: (PanoConfig) -> Unit) = change(config)
        override suspend fun initDatabase() { dbInits++ }
        override suspend fun userStore(): HostSsoUserStore = store

        override suspend fun registerAdmin(username: String, email: String, password: String): Long {
            require(password.length >= 6)
            return MemoryStore.U(store.users.size + 1L, username, email, true, password).also { store.users += it }.id
        }

        override suspend fun markInstaller(ownerId: Long, username: String) {
            if (failMarkInstaller-- > 0) throw IllegalStateException("db went away")
            installer = ownerId
        }

        override suspend fun connectPlatform(code: String, apiUrl: String?) {
            val url = PanoApiManager.normalizeApiUrl(apiUrl!!)!!
            connection = PanoApiManager.authorizePlatform(webClient, url, code, "1.0.0-alpha.520")
            connectedApiUrl = url
        }

        override suspend fun finishSetup() { config.setup.step = 5 }
    }

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        plane = FakeControlPlane(vertx, secret).start()
        webClient = WebClient.create(vertx)
        logs.clear()
    }

    @AfterEach
    fun tearDown() {
        plane.stop()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun client() = PanoHostClient(vertx, plane.baseUrl, secret, { logs += it })

    private fun setup(target: MemoryTarget, attempts: Int = 4, waits: MutableList<Long> = mutableListOf()) =
        HostedAutoSetup(client(), target, "w1", { logs += it }, attempts) { waits += it }

    private fun automatic(extraAdmin: Boolean = true, telemetry: Boolean = false) {
        plane.bootstrapStatus = 200
        plane.bootstrapData = JsonObject()
            .put("mode", "AUTOMATIC")
            .put("siteName", "Steve's Shop")
            .put("description", "Best survival server")
            .put("websiteUrl", "https://shop.panomc.site")
            .put("locale", "tr")
            .put("telemetry", telemetry)
            .put("owner", JsonObject().put("accountId", "acc-1").put("email", "owner@example.com").put("username", "owner"))
            .put(
                "extraAdmin",
                if (!extraAdmin) null
                else JsonObject().put("username", "helper").put("email", "helper@example.com").put("password", adminPassword)
            )
            .put("platform", JsonObject().put("code", handoverCode).put("expiresAt", 1).put("apiUrl", plane.baseUrl))
        plane.platformCodes[handoverCode] = JsonObject()
            .put("jwt", "platform-jwt").put("id", "platform-1").put("username", "owner").put("email", "owner@example.com")
    }

    private fun assertNoSecretsLogged() {
        val all = logs.joinToString("\n")
        assertFalse(all.contains(adminPassword), "extra admin password logged")
        assertFalse(all.contains(handoverCode), "handover code logged")
        assertFalse(all.contains(secret), "instance secret logged")
    }

    @Test
    fun `automatic bootstrap finishes setup with the owner, the extra admin and the platform connection`() = runBlocking {
        automatic()
        val target = MemoryTarget()

        assertEquals(HostedAutoSetup.Outcome.COMPLETED, setup(target).run())

        val config = target.config
        assertEquals(5, config.setup.step)
        assertEquals("Steve's Shop", config.websiteName)
        assertEquals("Best survival server", config.websiteDescription)
        assertEquals("https://shop.panomc.site", config.websiteUrl)
        assertEquals("tr", config.locale)
        assertEquals(false, config.telemetry!!.enabled)
        assertEquals(UsageMode.WEBSITE, config.usageMode)
        assertEquals(1, target.dbInits)

        val ownerId = target.store.mappings["pano_host_sso_account:acc-1"]!!
        val owner = target.store.user(ownerId)
        assertEquals("owner", owner.username, "the panomc.com username is kept when free")
        assertEquals("owner@example.com", owner.email)
        assertTrue(owner.admin)
        assertEquals(ownerId, target.installer)

        val helper = target.store.users.single { it.username == "helper" }
        assertTrue(helper.admin)
        assertEquals(adminPassword, helper.password)

        assertEquals("platform-1", target.connection!!.platformId)
        assertEquals(plane.baseUrl, target.connectedApiUrl)
        assertTrue(plane.platformCodes.isEmpty(), "handover code redeemed")

        assertEquals(true, plane.setupCompleted)
        assertEquals(true, plane.ssoSupported)
        assertEquals(JsonObject(), plane.requests.first { it.path.endsWith("/bootstrap") }.body)
        assertNoSecretsLogged()
    }

    @Test
    fun `a failed platform connect still finishes setup`() = runBlocking {
        automatic(extraAdmin = false, telemetry = true)
        plane.platformCodes.clear()
        val target = MemoryTarget()

        assertEquals(HostedAutoSetup.Outcome.COMPLETED, setup(target).run())

        assertEquals(5, target.config.setup.step)
        assertNull(target.connection)
        assertEquals(true, target.config.telemetry!!.enabled)
        assertEquals(1, target.store.users.size)
        assertEquals(true, plane.setupCompleted)
        assertTrue(logs.any { it.contains("could not connect") })
        assertNoSecretsLogged()
    }

    @Test
    fun `manual bootstrap only prefills the installer`() = runBlocking {
        plane.bootstrapStatus = 200
        plane.bootstrapData = JsonObject().put("mode", "MANUAL").put(
            "prefill",
            JsonObject().put("siteName", "Legacy Land").put("description", "")
                .put("websiteUrl", "https://legacy.panomc.site/").put("locale", "ru").put("telemetry", false)
        )
        val target = MemoryTarget()
        val usageMode = target.config.usageMode

        assertEquals(HostedAutoSetup.Outcome.PREFILLED, setup(target).run())

        assertEquals(0, target.config.setup.step)
        assertEquals("Legacy Land", target.config.websiteName)
        assertEquals("https://legacy.panomc.site", target.config.websiteUrl)
        assertEquals("ru", target.config.locale)
        assertEquals(false, target.config.telemetry!!.enabled)
        assertEquals(usageMode, target.config.usageMode, "the installer still asks the usage mode")
        assertEquals(0, target.dbInits)
        assertTrue(target.store.users.isEmpty())
        assertNull(plane.setupCompleted)
    }

    @Test
    fun `a refused bootstrap leaves the installer untouched`() = runBlocking {
        for ((status, code) in listOf(404 to "NO_BOOTSTRAP", 409 to "BOOTSTRAP_DONE")) {
            plane.bootstrapStatus = status
            plane.bootstrapError = code
            val target = MemoryTarget()
            val before = target.config.toString()

            assertEquals(HostedAutoSetup.Outcome.NOTHING_TO_DO, setup(target).run())
            assertEquals(before, target.config.toString())
            assertEquals(0, target.dbInits)
        }

        plane.bootstrapStatus = 401
        plane.bootstrapError = "INVALID_TOKEN"
        val waits = mutableListOf<Long>()
        val target = MemoryTarget()
        val before = target.config.toString()
        assertEquals(HostedAutoSetup.Outcome.UNAVAILABLE, setup(target, waits = waits).run())
        assertTrue(waits.isEmpty(), "a rejected secret is final")
        assertEquals(before, target.config.toString())
        assertNull(plane.setupCompleted)
    }

    @Test
    fun `an unreachable control plane is retried a few times and then leaves the installer`() = runBlocking {
        automatic()
        plane.failBootstrap.set(10)
        val waits = mutableListOf<Long>()
        val target = MemoryTarget()

        assertEquals(HostedAutoSetup.Outcome.UNAVAILABLE, setup(target, attempts = 3, waits = waits).run())
        assertEquals(listOf(2_000L, 4_000L), waits)
        assertEquals(0, target.config.setup.step)

        plane.failBootstrap.set(1)
        assertEquals(HostedAutoSetup.Outcome.COMPLETED, setup(target, attempts = 3).run())
    }

    @Test
    fun `a started or finished setup never asks for the bootstrap`() = runBlocking {
        automatic()
        for (step in listOf(1, 4, 5)) {
            val target = MemoryTarget().also { it.config.setup.step = step }
            assertEquals(HostedAutoSetup.Outcome.SKIPPED, setup(target).run())
        }

        assertTrue(plane.requests.isEmpty())
    }

    @Test
    fun `a crashed first boot is retried without duplicating users`() = runBlocking {
        automatic()
        val target = MemoryTarget().also { it.failMarkInstaller = 1 }

        assertEquals(HostedAutoSetup.Outcome.FAILED, setup(target).run())
        assertEquals(0, target.config.setup.step)
        assertNull(plane.setupCompleted)

        automatic()
        assertEquals(HostedAutoSetup.Outcome.COMPLETED, setup(target).run())
        assertEquals(5, target.config.setup.step)
        assertEquals(2, target.store.users.size, "owner and extra admin reused")
        assertEquals(target.store.mappings["pano_host_sso_account:acc-1"], target.installer)
        assertEquals(2, target.dbInits)
        assertNoSecretsLogged()
    }

    @Test
    fun `an extra admin whose username is taken is skipped and the owner gets a suffix`() = runBlocking {
        automatic()
        val target = MemoryTarget()
        target.store.users += MemoryStore.U(1, "owner", "player@example.com", false, "x")
        target.store.users += MemoryStore.U(2, "helper", "other@example.com", false, "x")

        assertEquals(HostedAutoSetup.Outcome.COMPLETED, setup(target).run())

        val owner = target.store.user(target.store.mappings["pano_host_sso_account:acc-1"]!!)
        assertEquals("owner2", owner.username)
        assertTrue(owner.admin)
        assertFalse(target.store.user(2).admin, "the existing helper is never promoted")
        assertEquals(3, target.store.users.size)
        assertTrue(logs.any { it.contains("extra admin helper not created") })
        assertNoSecretsLogged()
    }

    @Test
    fun `setupCompleted falls back for a control plane that does not know it`() = runBlocking {
        plane.legacyCapabilities = true

        assertTrue(client().announceCapabilities(ssoSupported = true, setupCompleted = true))
        assertEquals(true, plane.ssoSupported)
        assertNull(plane.setupCompleted)
        assertEquals(2, plane.requests.size)
    }

    @Test
    fun `bootstrap payloads never print their secrets`() = runBlocking {
        automatic()
        val bootstrap = client().bootstrap() as PanoHostClient.Bootstrap.Automatic

        assertEquals(adminPassword, bootstrap.extraAdmin!!.password)
        assertEquals(handoverCode, bootstrap.platform!!.code)
        assertFalse(bootstrap.toString().contains(adminPassword))
        assertFalse(bootstrap.toString().contains(handoverCode))
    }

    @Test
    fun `platform authorize rejects bad api urls and unknown codes`() = runBlocking {
        assertNull(PanoApiManager.normalizeApiUrl("javascript:alert(1)"))
        assertNull(PanoApiManager.normalizeApiUrl("https://user@api.panomc.com"))
        assertEquals("https://api.panomc.com", PanoApiManager.normalizeApiUrl(" https://api.panomc.com/ "))

        val error = runCatching {
            PanoApiManager.authorizePlatform(webClient, plane.baseUrl, "unknown-code", "1.0.0-alpha.520")
        }.exceptionOrNull()
        assertEquals("PanoConnectFailed", error?.javaClass?.simpleName)
    }
}
