package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeUpdateAvailabilityTest {
    @Test
    fun `a node on an older release has an update`() {
        assertTrue(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = "1.0.0-alpha.500",
                platformVersion = "1.0.0-alpha.513",
                nodeJarSha256 = null,
                servedSha256 = null
            )
        )
    }

    @Test
    fun `a node on this very release does not`() {
        assertFalse(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = "1.0.0-alpha.513",
                platformVersion = "1.0.0-alpha.513",
                nodeJarSha256 = null,
                servedSha256 = null
            )
        )
    }

    @Test
    fun `two local builds are told apart by their bytes and nothing else`() {
        assertTrue(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = NodeUpdateAvailability.LOCAL_BUILD,
                platformVersion = NodeUpdateAvailability.LOCAL_BUILD,
                nodeJarSha256 = "aaaa",
                servedSha256 = "bbbb"
            )
        )

        assertFalse(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = NodeUpdateAvailability.LOCAL_BUILD,
                platformVersion = NodeUpdateAvailability.LOCAL_BUILD,
                nodeJarSha256 = "AAAA",
                servedSha256 = "aaaa"
            )
        )
    }

    @Test
    fun `a local build with no checksum to compare offers nothing`() {
        // Both sides say `local-build` forever, so without the bytes there is no question to
        // answer -- and an update badge that is always on is worse than none.
        assertFalse(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = NodeUpdateAvailability.LOCAL_BUILD,
                platformVersion = NodeUpdateAvailability.LOCAL_BUILD,
                nodeJarSha256 = null,
                servedSha256 = "aaaa"
            )
        )

        assertFalse(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = NodeUpdateAvailability.LOCAL_BUILD,
                platformVersion = NodeUpdateAvailability.LOCAL_BUILD,
                nodeJarSha256 = "aaaa",
                servedSha256 = null
            )
        )
    }

    @Test
    fun `a development Pano against a released node still compares the bytes`() {
        assertTrue(
            NodeUpdateAvailability.isAvailable(
                nodeVersion = "1.0.0-alpha.500",
                platformVersion = NodeUpdateAvailability.LOCAL_BUILD,
                nodeJarSha256 = "aaaa",
                servedSha256 = "bbbb"
            )
        )
    }

    @Test
    fun `the same bytes on both sides is a no-op update`() {
        // What `POST /api/panel/nodes/:id/update` checks before pushing SELF_UPDATE: restarting
        // a node to install the jar it is already running costs every server on it a minute.
        assertTrue(NodeUpdateAvailability.isSameJar("aaaa", "AAAA"))
        assertTrue(NodeUpdateAvailability.isSameJar(" aaaa ", "aaaa"))
        assertFalse(NodeUpdateAvailability.isSameJar("aaaa", "bbbb"))
    }

    @Test
    fun `an unknown checksum is never taken for a match`() {
        assertFalse(NodeUpdateAvailability.isSameJar(null, "aaaa"))
        assertFalse(NodeUpdateAvailability.isSameJar("aaaa", null))
        assertFalse(NodeUpdateAvailability.isSameJar("  ", "aaaa"))
        assertFalse(NodeUpdateAvailability.isSameJar(null, null))
    }

    @Test
    fun `a node that reported no version is never told it is out of date`() {
        assertFalse(NodeUpdateAvailability.isAvailable(null, "1.0.0", null, null))
        assertFalse(NodeUpdateAvailability.isAvailable("  ", "1.0.0", null, null))
        assertFalse(NodeUpdateAvailability.isAvailable("1.0.0-alpha.1", null, null, null))
    }
}
