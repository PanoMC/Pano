package com.panomc.platform.archive.instance

import com.panomc.platform.archive.PanoArcException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** An archived config.conf cannot pull in local files or URLs while a restore is only being checked. */
class ConfigRewriterIncludeTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `a plain config still parses`() {
        val config = ConfigRewriter.parseHocon("config-version = 41\ndatabase { prefix = \"pano_\" }\n")

        assertEquals(41, config.getInteger("config-version"))
        assertEquals("pano_", config.getJsonObject("database").getString("prefix"))
    }

    @Test
    fun `file, classpath and bare includes are refused`() {
        val secret = File(temp, "secret.conf").apply { writeText("stolen = \"yes\"\n") }

        listOf(
            "include file(\"${secret.absolutePath.replace("\\", "\\\\")}\")",
            "include \"${secret.absolutePath.replace("\\", "\\\\")}\"",
            "include classpath(\"log4j2.xml\")",
            "include required(file(\"${secret.absolutePath.replace("\\", "\\\\")}\"))"
        ).forEach { include ->
            assertThrows<PanoArcException>(include) { ConfigRewriter.parseHocon("$include\na = 1\n") }
        }
    }

    @Test
    fun `a url include makes no request`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 1500
            val contacted = AtomicBoolean(false)
            val listener = thread {
                try {
                    server.accept().close()
                    contacted.set(true)
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }

            assertThrows<PanoArcException> {
                ConfigRewriter.parseHocon("include url(\"http://127.0.0.1:${server.localPort}/x.conf\")\na = 1\n")
            }

            listener.join()
            assertFalse(contacted.get())
        }
    }
}
