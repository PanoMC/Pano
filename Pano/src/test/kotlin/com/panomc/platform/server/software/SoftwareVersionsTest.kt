package com.panomc.platform.server.software

import com.panomc.platform.server.software.dto.SoftwareCatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The version strings are real ones, taken from the live listings on 2026-09-21.
 *
 * The bug this exists for: a Velocity created without a version installed `velocity
 * 4.2.1-SNAPSHOT`, which is a build compiled for Java 25, onto a host whose newest runtime was 21
 * -- the proxy then crash-looped. "Newest" was never the same question as "released".
 */
class SoftwareVersionsTest {
    @Test
    fun `reads the development suffixes every upstream uses`() {
        assertTrue(SoftwareVersions.isPrerelease("4.2.1-SNAPSHOT"))
        assertTrue(SoftwareVersions.isPrerelease("3.6.0-snapshot"))
        assertTrue(SoftwareVersions.isPrerelease("1.20.1-R0.1-SNAPSHOT"))
        assertTrue(SoftwareVersions.isPrerelease("26.3-rc-3"))
        assertTrue(SoftwareVersions.isPrerelease("1.21-pre1"))
        assertTrue(SoftwareVersions.isPrerelease("1.0.0-beta.2"))
        assertTrue(SoftwareVersions.isPrerelease("2.0.0-alpha"))
    }

    @Test
    fun `a release is not mistaken for a snapshot`() {
        listOf("4.2.0", "3.5.1", "1.21.8", "26.1.2", "1.8.8", "1.0.10").forEach { version ->
            assertTrue(SoftwareVersions.isStable(version), version)
        }

        // The marker has to be a suffix of its own; "alphabet" is not "alpha".
        assertFalse(SoftwareVersions.isPrerelease("1.21-alphabet"))
        assertFalse(SoftwareVersions.isPrerelease("1.21.4"))
    }

    @Test
    fun `keeps snapshots but sorts them after the releases`() {
        val velocity = listOf(
            "4.2.1-SNAPSHOT",
            "4.2.0",
            "4.1.2-SNAPSHOT",
            "4.1.1",
            "3.5.1",
            "3.6.0-SNAPSHOT"
        )

        assertEquals(
            listOf("4.2.0", "4.1.1", "3.5.1", "4.2.1-SNAPSHOT", "4.1.2-SNAPSHOT", "3.6.0-SNAPSHOT"),
            SoftwareVersions.stableFirst(velocity)
        )
    }

    @Test
    fun `recommends the newest release rather than the newest build`() {
        assertEquals("4.2.0", SoftwareVersions.recommended(listOf("4.2.1-SNAPSHOT", "4.2.0", "4.1.1")))
        assertEquals("1.21.11", SoftwareVersions.recommended(listOf("1.21.11", "1.21.10")))
    }

    @Test
    fun `falls back to a snapshot only when a software has never released one`() {
        assertEquals("5.0.0-SNAPSHOT", SoftwareVersions.recommended(listOf("5.0.0-SNAPSHOT", "4.9.9-SNAPSHOT")))
        assertNull(SoftwareVersions.recommended(emptyList()))
    }

    @Test
    fun `the catalog entry carries the recommended version to the wizard`() {
        val entry = SoftwareCatalogEntry(
            id = "velocity",
            name = "Velocity",
            recommended = false,
            versions = listOf("4.2.0", "4.1.1"),
            recommendedVersion = SoftwareVersions.recommended(listOf("4.2.0", "4.1.1"))
        )

        val json = entry.toJsonObject()

        assertEquals("velocity", json.getString("id"))
        assertEquals("4.2.0", json.getString("recommendedVersion"))
        assertEquals(2, json.getJsonArray("versions").size())
    }
}
