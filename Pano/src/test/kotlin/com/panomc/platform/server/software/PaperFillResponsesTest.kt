package com.panomc.platform.server.software

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Captured from the live API on 2026-09-21, trimmed to the fields the catalog reads.
 *
 * The point of the fixtures is that they are real: the previous code read `versions` as an array
 * and the build's file name out of `downloads.application`, which is what v2 answered, and nothing
 * noticed when v2 was retired and the shapes changed.
 */
class PaperFillResponsesTest {
    private val paperProject = JsonObject(
        """
        {
          "project": { "id": "paper", "name": "Paper" },
          "versions": {
            "26.3": ["26.3", "26.3-rc-3"],
            "26.1": ["26.1.2", "26.1.1"],
            "1.21": ["1.21.11", "1.21.10", "1.21.8"],
            "1.20": ["1.20.6", "1.20.1"],
            "1.8": ["1.8.8"]
          }
        }
        """.trimIndent()
    )

    private val paperBuilds = JsonArray(
        """
        [
          {
            "id": 60,
            "time": "2025-09-06T21:50:11.982Z",
            "channel": "STABLE",
            "downloads": {
              "server:default": {
                "name": "paper-1.21.8-60.jar",
                "checksums": { "sha256": "8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e" },
                "size": 52811717,
                "url": "https://fill-data.papermc.io/v1/objects/8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e/paper-1.21.8-60.jar"
              }
            }
          },
          {
            "id": 59,
            "channel": "STABLE",
            "downloads": {
              "server:default": {
                "name": "paper-1.21.8-59.jar",
                "checksums": { "sha256": "f8c67bdb8de57e16d972e57c11d2f1b6c74d9e796d798e8ba92461ecdfe146ca" },
                "url": "https://fill-data.papermc.io/v1/objects/f8c67bdb/paper-1.21.8-59.jar"
              }
            }
          }
        ]
        """.trimIndent()
    )

    @Test
    fun `flattens the version groups newest first`() {
        val versions = PaperFillResponses.versions(paperProject)

        assertEquals(
            listOf("26.3", "26.3-rc-3", "26.1.2", "26.1.1", "1.21.11", "1.21.10", "1.21.8", "1.20.6", "1.20.1", "1.8.8"),
            versions
        )
    }

    @Test
    fun `sorts version groups numerically rather than as text`() {
        // "1.9" sorts after "1.10" as text and before it as a version; "26.3" beats both.
        val project = JsonObject(
            """
            { "versions": { "1.9": ["1.9.4"], "1.10": ["1.10.2"], "26.3": ["26.3"] } }
            """.trimIndent()
        )

        assertEquals(listOf("26.3", "1.10.2", "1.9.4"), PaperFillResponses.versions(project))
    }

    @Test
    fun `an unreachable or reshaped project response lists nothing rather than throwing`() {
        assertTrue(PaperFillResponses.versions(null).isEmpty())
        assertTrue(PaperFillResponses.versions(JsonObject()).isEmpty())
        assertTrue(PaperFillResponses.versions(JsonObject().put("versions", JsonObject())).isEmpty())
    }

    @Test
    fun `takes the newest build and its download`() {
        val build = PaperFillResponses.latestBuild(paperBuilds)

        assertEquals(60, build?.build)
        assertEquals(
            "https://fill-data.papermc.io/v1/objects/8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e/paper-1.21.8-60.jar",
            build?.downloadUrl
        )
        assertEquals("paper-1.21.8-60.jar", build?.fileName)
        assertEquals("8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e", build?.sha256)
        assertEquals("STABLE", build?.channel)
    }

    @Test
    fun `prefers a stable build over a newer experimental one`() {
        val builds = JsonArray(
            """
            [
              { "id": 12, "channel": "ALPHA", "downloads": { "server:default": {
                  "name": "paper-a.jar", "url": "https://fill-data.papermc.io/v1/objects/a/paper-a.jar" } } },
              { "id": 11, "channel": "RECOMMENDED", "downloads": { "server:default": {
                  "name": "paper-b.jar", "url": "https://fill-data.papermc.io/v1/objects/b/paper-b.jar" } } }
            ]
            """.trimIndent()
        )

        assertEquals(11, PaperFillResponses.latestBuild(builds)?.build)
    }

    @Test
    fun `falls back to the newest build when nothing is marked stable`() {
        val builds = JsonArray(
            """
            [
              { "id": 12, "channel": "ALPHA", "downloads": { "server:default": {
                  "url": "https://fill-data.papermc.io/v1/objects/a/paper-a.jar" } } },
              { "id": 11, "channel": "BETA", "downloads": { "server:default": {
                  "url": "https://fill-data.papermc.io/v1/objects/b/paper-b.jar" } } }
            ]
            """.trimIndent()
        )

        assertEquals(12, PaperFillResponses.latestBuild(builds)?.build)
    }

    @Test
    fun `ignores a build whose download is missing or points somewhere else`() {
        val builds = JsonArray(
            """
            [
              { "id": 12, "channel": "STABLE", "downloads": { "server:mojmap": {
                  "url": "https://fill-data.papermc.io/v1/objects/a/paper-a.jar" } } },
              { "id": 11, "channel": "STABLE", "downloads": { "server:default": {
                  "url": "https://evil.example.com/paper-b.jar" } } }
            ]
            """.trimIndent()
        )

        assertNull(PaperFillResponses.latestBuild(builds))
        assertNull(PaperFillResponses.latestBuild(JsonArray()))
        assertNull(PaperFillResponses.latestBuild(null))
    }

    @Test
    fun `reads a velocity build, where a version group is not a minecraft version`() {
        val builds = JsonArray(
            """
            [{ "id": 615, "channel": "RECOMMENDED", "downloads": { "server:default": {
                "name": "velocity-3.5.1-615.jar",
                "checksums": { "sha256": "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3" },
                "url": "https://fill-data.papermc.io/v1/objects/b4e3164d/velocity-3.5.1-615.jar" } } }]
            """.trimIndent()
        )

        val build = PaperFillResponses.latestBuild(builds)

        assertEquals(615, build?.build)
        assertEquals("velocity-3.5.1-615.jar", build?.fileName)
    }
}
