package com.panomc.platform.gate

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.update.CompatibilityStore
import com.panomc.platform.update.CompatibleHit
import com.panomc.platform.update.CompatibleQuery
import com.panomc.platform.update.DownloadedResource
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * CX-06 items 0.1 and 10: the boot reconcile is a suspend step (the install code runs its own `executeBlocking`
 * work, which queued behind a `runBlocking` inside `executeBlocking` and never ran), and a download is checked
 * against the hash the store gave.
 */
class BootReconcileTest {
    @TempDir
    lateinit var folder: File

    private lateinit var vertx: Vertx

    private var level = 0
    private var running = false
    private val fileBytes = "plugin-v2".toByteArray()

    private val catalog = object : ResourceCatalog {
        override fun installed() = listOf(
            InstalledResource(
                "market", ResourceType.PLUGIN, "Market", if (level == 0) "1.0.0" else "2.0.0", level,
                ApiLevelGate.check(level), running
            )
        )

        override fun inspect(file: File, type: ResourceType) = InspectedFile("market", "2.0.0", 1)
    }

    private fun store(hash: String?) = object : CompatibilityStore {
        override suspend fun newestCompatible(query: CompatibleQuery) =
            listOf(CompatibleHit("market", ResourceType.PLUGIN, "vid", "2.0.0", 1, hash))

        override suspend fun download(hit: CompatibleHit, targetFolder: File): DownloadedResource {
            targetFolder.mkdirs()

            val file = File(targetFolder, "market.jar").apply { writeBytes(fileBytes) }

            return DownloadedResource(file, hit.hash)
        }
    }

    /** The shape of `InstallManager.installResource`: its own `executeBlocking(...).coAwait()` before the plugin starts. */
    private val installer = ResourceInstaller { _, _, _ ->
        vertx.executeBlocking<Unit> { }.coAwait()

        level = 1
        running = true

        null
    }

    private fun reconciler(hash: String?) =
        CompatibilityReconciler(catalog, store(hash), installer, { }, { File(folder, "dl") })

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        level = 0
        running = false
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    private fun sha256() = MessageDigest.getInstance("SHA-256").digest(fileBytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a boot with one plugin that needs a compatible update installs it from a suspend step`() {
        // Main.init is a coroutine on the Vert.x dispatcher; the reconcile is awaited there, not blocked on a worker.
        val report = runBlocking {
            withTimeout(20_000) {
                CoroutineScope(vertx.dispatcher()).async { reconciler(null).reconcile(atBoot = true) }.await()
            }
        }

        assertEquals(1, report.installed.size)
        assertEquals("2.0.0", report.installed[0].installedVersion)
        assertTrue(running)
    }

    @Test
    fun `the old shape, runBlocking inside executeBlocking, cannot finish`() {
        // Documents the deadlock the boot had: the install's own executeBlocking queues behind the blocked worker.
        val finished = vertx.executeBlocking<Boolean> {
            try {
                runBlocking { withTimeout(1_500) { reconciler(null).reconcile(atBoot = true) } }

                true
            } catch (_: Exception) {
                false
            }
        }.toCompletionStage().toCompletableFuture().get()

        assertEquals(false, finished)
    }

    @Test
    fun `a file that differs from the hash the store gave is refused as DOWNLOAD_MISMATCH`() {
        val report = runBlocking { reconciler("0".repeat(64)).reconcile(atBoot = true) }

        assertEquals(ReconcileOutcome.REFUSED, report.entries[0].outcome)
        assertTrue(report.entries[0].lastError!!.startsWith("DOWNLOAD_MISMATCH"))
        assertEquals(0, level)
    }

    @Test
    fun `a file that matches the hash is installed, and no hash keeps today's behaviour`() {
        val matching = runBlocking { reconciler(sha256()).reconcile(atBoot = true) }

        assertEquals(ReconcileOutcome.INSTALLED, matching.entries[0].outcome)

        level = 0
        running = false

        val without = runBlocking { reconciler(null).reconcile(atBoot = true) }

        assertEquals(ReconcileOutcome.INSTALLED, without.entries[0].outcome)
    }
}
