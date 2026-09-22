package com.panomc.platform.server.software

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixtures captured from ci.md-5.net on 2026-09-22, trimmed to the fields the catalog reads. */
class BungeeCordJenkinsTest {
    private val builds = JsonObject(
        """
        {
          "_class": "hudson.maven.MavenModuleSet",
          "builds": [
            { "number": 2096, "result": "SUCCESS" },
            { "number": 2095, "result": "SUCCESS" },
            { "number": 2094, "result": "FAILURE" },
            { "number": 2093, "result": null },
            { "number": 2092, "result": "SUCCESS" }
          ]
        }
        """.trimIndent()
    )

    private val build = JsonObject(
        """
        {
          "_class": "hudson.maven.MavenModuleSetBuild",
          "number": 2096,
          "timestamp": 1789506533972,
          "artifacts": [
            { "fileName": "BungeeCord.jar", "relativePath": "bootstrap/target/BungeeCord.jar" },
            { "fileName": "cmd_alert.jar", "relativePath": "module/cmd-alert/target/cmd_alert.jar" }
          ],
          "fingerprint": [
            { "fileName": "com.google.code.gson:gson-2.13.2.jar", "hash": "a2c47e14ce5e956105458fe455f5d542" },
            { "fileName": "net.md-5:BungeeCord-sources.jar", "hash": "38a94377f5e6c7eeae9fef8dadab43e8" },
            { "fileName": "net.md-5:BungeeCord.jar", "hash": "689e46311c16ce9f2bf5a152f4a8ed75" }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `offers only the builds that finished, newest first`() {
        assertEquals(listOf("2096", "2095", "2092"), BungeeCordJenkins.successfulBuilds(builds))
    }

    @Test
    fun `an unreachable jenkins is an empty list, not a failure`() {
        assertEquals(emptyList<String>(), BungeeCordJenkins.successfulBuilds(null))
        assertEquals(emptyList<String>(), BungeeCordJenkins.successfulBuilds(JsonObject()))
    }

    @Test
    fun `resolves lastSuccessfulBuild into its real number`() {
        assertEquals("2096", BungeeCordJenkins.buildNumber(build))
        assertNull(BungeeCordJenkins.buildNumber(JsonObject()))
    }

    @Test
    fun `only offers a build that archived the proxy jar`() {
        assertTrue(BungeeCordJenkins.hasArtifact(build))

        val withoutArtifacts = JsonObject("""{ "number": 2090, "artifacts": [] }""")

        assertFalse(BungeeCordJenkins.hasArtifact(withoutArtifacts))
        assertFalse(BungeeCordJenkins.hasArtifact(null))
    }

    @Test
    fun `finds the maven fingerprint by the file name after the group id`() {
        // Not the archived jar's checksum -- see the doc on the function, which is why the
        // catalog does not hand this to a node. The parsing is still pinned: the entry is named
        // `<groupId>:<file>`, and `BungeeCord-sources.jar` must not win.
        assertEquals("689e46311c16ce9f2bf5a152f4a8ed75", BungeeCordJenkins.mavenFingerprintMd5(build))
    }

    @Test
    fun `ignores a fingerprint that is not a hash`() {
        val broken = JsonObject(
            """{ "fingerprint": [ { "fileName": "net.md-5:BungeeCord.jar", "hash": "unknown" } ] }"""
        )

        assertNull(BungeeCordJenkins.mavenFingerprintMd5(broken))
        assertNull(BungeeCordJenkins.mavenFingerprintMd5(JsonObject()))
    }
}
