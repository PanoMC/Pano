package com.panomc.platform.server.software

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures captured from hub.spigotmc.org on 2026-09-22, trimmed to the entries that matter.
 *
 * The listing is the real thing: an Nginx directory index whose four thousand entries are mostly
 * BuildTools build numbers, with the versions mixed in and sorted as text. Everything this parser
 * is for -- which entries are revisions, and what order they belong in -- is invisible unless the
 * fixture keeps that shape.
 */
class SpigotHubResponsesTest {
    private val listing = """
        <html>
        <head><title>Index of /versions/</title></head>
        <body>
        <h1>Index of /versions/</h1><hr><pre><a href="../">../</a>
        <a href="1.10.2.json">1.10.2.json</a>                                        28-Sep-2025 01:10   325
        <a href="1.13-pre7.json">1.13-pre7.json</a>                                   21-Jul-2018 04:58   325
        <a href="1.21.8.json">1.21.8.json</a>                                         15-Sep-2026 15:15   534
        <a href="1.21.json">1.21.json</a>                                             15-Sep-2026 15:15   534
        <a href="1.8.8.json">1.8.8.json</a>                                           17-Nov-2016 21:44   323
        <a href="1.9.json">1.9.json</a>                                               17-Nov-2016 21:44   323
        <a href="26.1.2.json">26.1.2.json</a>                                         15-Sep-2026 15:15   534
        <a href="26.3.json">26.3.json</a>                                             15-Sep-2026 15:15   534
        <a href="996.json">996.json</a>                                               17-Nov-2016 21:53   323
        <a href="latest.json">latest.json</a>                                         15-Sep-2026 15:15   534
        </pre><hr></body>
        </html>
    """.trimIndent()

    private val modernVersion = JsonObject(
        """
        {
          "name": "4534",
          "description": "Jenkins build 4534",
          "refs": { "Spigot": "7c52c662ccee93e52a21c54a1b8f93721e4ec773" },
          "toolsVersion": 181,
          "javaVersions": [65, 69]
        }
        """.trimIndent()
    )

    private val legacyVersion = JsonObject(
        """
        {
          "name": "582b",
          "description": "Jenkins build 582b (hotfix 3)",
          "refs": { "Spigot": "3c60ece1480c9b686b06f33daa6ca23c8883e9f2" }
        }
        """.trimIndent()
    )

    @Test
    fun `lists only version-shaped entries`() {
        val versions = SpigotHubResponses.versions(listing)

        assertTrue(versions.contains("1.21.8"))
        assertTrue(versions.contains("1.21"))

        // A build number, a pre-release and the "latest" alias are all entries in the same index.
        assertFalse(versions.contains("996"))
        assertFalse(versions.contains("1.13-pre7"))
        assertFalse(versions.contains("latest"))
    }

    @Test
    fun `sorts the listing newest first rather than alphabetically`() {
        val versions = SpigotHubResponses.versions(listing)

        assertEquals(listOf("26.3", "26.1.2", "1.21.8", "1.21", "1.10.2", "1.9", "1.8.8"), versions)
    }

    @Test
    fun `an unreachable listing is an empty list, not a failure`() {
        assertEquals(emptyList<String>(), SpigotHubResponses.versions(null))
        assertEquals(emptyList<String>(), SpigotHubResponses.versions(""))
    }

    @Test
    fun `reads the java version out of the class file majors`() {
        assertEquals(21, SpigotHubResponses.javaMajor(modernVersion))
    }

    @Test
    fun `a revision that names no java versions leaves the choice to the caller`() {
        assertNull(SpigotHubResponses.javaMajor(legacyVersion))
        assertNull(SpigotHubResponses.javaMajor(null))
    }

    @Test
    fun `maps the class file majors the contract pins`() {
        assertEquals(8, SpigotHubResponses.javaMajorOf(52))
        assertEquals(11, SpigotHubResponses.javaMajorOf(55))
        assertEquals(16, SpigotHubResponses.javaMajorOf(60))
        assertEquals(17, SpigotHubResponses.javaMajorOf(61))
        assertEquals(21, SpigotHubResponses.javaMajorOf(65))
    }

    @Test
    fun `works out a class file major it has never seen`() {
        assertEquals(25, SpigotHubResponses.javaMajorOf(69))
        assertNull(SpigotHubResponses.javaMajorOf(45))
    }

    @Test
    fun `takes the lower bound of the accepted range`() {
        // 1.16.5 is [52, 60]: BuildTools builds it on Java 8 and refuses past 16, so the bottom
        // of the range is the one that works on the most hosts.
        val legacyRange = JsonObject("""{ "javaVersions": [52, 60] }""")

        assertEquals(8, SpigotHubResponses.javaMajor(legacyRange))
    }
}
