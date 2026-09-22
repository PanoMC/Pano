package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaRuntimeInstaller
import com.panomc.node.java.JavaRuntimeService
import com.panomc.node.java.JavaTarget
import com.panomc.node.java.JavaUser
import com.panomc.node.java.ManagedRuntimeMarker
import com.panomc.node.net.JavaInstallMessage
import com.panomc.node.net.JavaRemoveMessage
import com.panomc.node.task.TaskSink
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * `JAVA_INSTALL`, `JAVA_REMOVE` and `JAVA_CATALOG` as Pano sees them (SM-63): the task frames they
 * end in and the catalog they answer with. Runtimes are planted on disk rather than downloaded;
 * majors 42 and 43 so the host's own JDKs never match.
 */
class JavaRuntimeServiceTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private data class Frame(val taskId: String, val status: String, val kind: String, val text: String?, val extra: JsonObject?)

    private class RecordingSink : TaskSink {
        val frames = mutableListOf<Frame>()

        override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {
            frames.add(Frame(taskId, "RUNNING", kind, message, null))
        }

        override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
            frames.add(Frame(taskId, "DONE", kind, message, extra))
        }

        override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
            frames.add(Frame(taskId, "FAILED", kind, error, extra))
        }

        fun last() = frames.last()
    }

    private val sink = RecordingSink()
    private val announced = mutableListOf<JsonObject>()
    private var users: List<JavaUser> = emptyList()

    private fun plant(name: String, version: String, managed: Boolean): File {
        val home = File(dataDir, "java/$name")

        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("")
        File(home, "release").writeText("JAVA_VERSION=\"$version\"\n")

        if (managed) {
            File(home, ManagedRuntimeMarker.FILE).writeText("""{"vendor":"temurin","version":"$version+1"}""")
        }

        return home
    }

    private fun service(http: JavaPackageResolver.Http = JavaPackageResolver.Http { JavaPackageResolver.Http.Response(200, "[]") }): JavaRuntimeService {
        val locator = JavaRuntimeLocator(dataDir)
        val resolver = JavaPackageResolver(JavaTarget("linux", "x64"), http)

        return JavaRuntimeService(
            locator = locator,
            installer = JavaRuntimeInstaller(dataDir, resolver, locator, logger, sanityCheck = {}),
            resolver = resolver,
            tasks = sink,
            announce = { announced.add(it) },
            users = { users },
            autoDownload = { true },
            logger = logger
        )
    }

    @Test
    fun `removes a managed runtime and announces the new list`() {
        val home = plant("temurin-42.0.1", "42.0.1", managed = true)

        service().remove(JavaRemoveMessage("t1", 42))

        assertEquals("DONE", sink.last().status)
        assertEquals("JAVA_REMOVE", sink.last().kind)
        assertFalse(home.exists())
        assertTrue(announced.isNotEmpty())
        assertTrue(announced.last().getJsonArray("javaRuntimes").none { (it as JsonObject).getInteger("major") == 42 })
    }

    @Test
    fun `refuses to remove a runtime a running server uses`() {
        val home = plant("temurin-42.0.1", "42.0.1", managed = true)

        users = listOf(JavaUser("srv-1", alive = true, javaHome = home.canonicalPath, pinnedMajor = null))

        service().remove(JavaRemoveMessage("t2", 42))

        val frame = sink.last()

        assertEquals("FAILED", frame.status)
        assertEquals(JavaRuntimeService.ERROR_IN_USE, frame.text)
        assertEquals(listOf("srv-1"), frame.extra!!.getJsonArray("serverUuids").list)
        assertTrue(home.exists())
    }

    @Test
    fun `refuses to remove the last runtime a server pins`() {
        plant("temurin-42.0.1", "42.0.1", managed = true)

        users = listOf(JavaUser("pinned", alive = false, javaHome = null, pinnedMajor = 42))

        service().remove(JavaRemoveMessage("t3", 42))

        assertEquals(JavaRuntimeService.ERROR_IN_USE, sink.last().text)
    }

    @Test
    fun `never removes a runtime the node did not install`() {
        val home = plant("my-jdk-43", "43.0.1", managed = false)

        service().remove(JavaRemoveMessage("t4", 43))

        assertEquals(JavaRuntimeService.ERROR_NOT_MANAGED, sink.last().text)
        assertTrue(home.exists())
    }

    @Test
    fun `removing a major that is not there is NOT_FOUND`() {
        service().remove(JavaRemoveMessage("t5", 42))

        assertEquals(JavaRuntimeService.ERROR_NOT_FOUND, sink.last().text)
    }

    @Test
    fun `an install that is already current is DONE at once`() {
        plant("temurin-42.0.1", "42.0.1", managed = true)

        val http = JavaPackageResolver.Http { url ->
            if (url.contains("adoptium")) {
                JavaPackageResolver.Http.Response(
                    200,
                    """[{"binary":{"package":{"link":"https://example.invalid/x.tar.gz","checksum":"ab","name":"x.tar.gz","size":1}},"version":{"openjdk_version":"42.0.1+1-LTS"}}]"""
                )
            } else {
                JavaPackageResolver.Http.Response(200, "[]")
            }
        }

        service(http).install(JavaInstallMessage("t6", 42))

        val frame = sink.last()

        assertEquals("DONE", frame.status)
        assertEquals("JAVA_INSTALL", frame.kind)
        assertTrue(frame.text!!.contains("already up to date"))
        assertEquals(true, frame.extra!!.getBoolean("alreadyCurrent"))
    }

    @Test
    fun `an install of a major nobody builds fails with the host named`() {
        service().install(JavaInstallMessage("t7", 42))

        val frame = sink.last()

        assertEquals("FAILED", frame.status)
        assertTrue(frame.text!!.contains("linux/x64"))
    }

    @Test
    fun `an out of range major is refused`() {
        service().install(JavaInstallMessage("t8", 0))

        assertEquals("INVALID_MAJOR", sink.last().text)
    }

    @Test
    fun `the catalog lists installed runtimes and what can be downloaded`() {
        val home = plant("temurin-42.0.1", "42.0.1", managed = true)

        users = listOf(JavaUser("srv-1", alive = true, javaHome = home.canonicalPath, pinnedMajor = null))

        val http = JavaPackageResolver.Http { url ->
            if (url.contains("adoptium") && url.contains("/latest/21/")) {
                JavaPackageResolver.Http.Response(
                    200,
                    """[{"binary":{"package":{"link":"https://example.invalid/j21.tar.gz","checksum":"ab","name":"j21.tar.gz","size":52059408}},"version":{"openjdk_version":"21.0.12.1+1-LTS"}}]"""
                )
            } else {
                JavaPackageResolver.Http.Response(200, "[]")
            }
        }

        val catalog = service(http).catalog()

        assertEquals(true, catalog.getBoolean("ok"))
        assertEquals("linux", catalog.getString("os"))
        assertEquals("glibc", catalog.getString("libc"))
        assertEquals(true, catalog.getBoolean("autoDownload"))
        assertNull(catalog.getString("catalogError"))

        val ours = catalog.getJsonArray("runtimes").map { it as JsonObject }.single { it.getInteger("major") == 42 }

        assertEquals(true, ours.getBoolean("managed"))
        assertEquals("42.0.1+1", ours.getString("version"))
        assertEquals(listOf("srv-1"), ours.getJsonArray("usedBy").list)

        val downloadable = catalog.getJsonArray("downloadable").map { it as JsonObject }

        assertEquals(listOf(8, 11, 16, 17, 21, 25), downloadable.map { it.getInteger("major") })

        val java21 = downloadable.single { it.getInteger("major") == 21 }

        assertEquals(true, java21.getBoolean("available"))
        assertEquals("temurin", java21.getString("vendor"))
        assertEquals("21.0.12.1+1", java21.getString("version"))
        assertEquals(52059408L, java21.getLong("size"))
        assertEquals(false, java21.getBoolean("updateAvailable"))

        assertEquals(false, downloadable.single { it.getInteger("major") == 16 }.getBoolean("available"))
    }

    @Test
    fun `an offline catalog still answers, with the reason`() {
        val catalog = service(JavaPackageResolver.Http { throw JavaPackageResolver.LookupFailedException("offline") }).catalog()

        assertEquals(true, catalog.getBoolean("ok"))
        assertTrue(catalog.getJsonArray("downloadable").isEmpty)
        assertTrue(catalog.getString("catalogError").contains("offline"))
    }
}
