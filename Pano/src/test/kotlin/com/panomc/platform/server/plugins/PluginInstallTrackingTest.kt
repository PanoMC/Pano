package com.panomc.platform.server.plugins

import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lifecycle of the row an install writes before the jar exists.
 *
 * A row that outlives a failed download claims a version the server does not have, and the update
 * check then offers to "update" a plugin that was never installed. A row deleted too eagerly loses
 * the provenance of a plugin that is sitting right there. Both are decided here.
 */
class PluginInstallTrackingTest {
    @Test
    fun `a finished install confirms its row`() {
        assertEquals(
            PluginInstallTracking.Outcome.CONFIRM,
            PluginInstallTracking.outcomeOf(ServerTaskKind.PLUGIN_INSTALL, ServerTaskStatus.DONE)
        )
    }

    @Test
    fun `a failed install takes its row with it`() {
        assertEquals(
            PluginInstallTracking.Outcome.DISCARD,
            PluginInstallTracking.outcomeOf(ServerTaskKind.PLUGIN_INSTALL, ServerTaskStatus.FAILED)
        )
    }

    @Test
    fun `an install still running decides nothing`() {
        ServerTaskStatus.entries
            .filterNot { it == ServerTaskStatus.DONE || it == ServerTaskStatus.FAILED }
            .forEach { status ->
                assertEquals(
                    PluginInstallTracking.Outcome.IGNORE,
                    PluginInstallTracking.outcomeOf(ServerTaskKind.PLUGIN_INSTALL, status),
                    "$status should not settle a row"
                )
            }
    }

    @Test
    fun `no other kind of task touches a plugin row`() {
        ServerTaskKind.entries
            .filterNot { it == ServerTaskKind.PLUGIN_INSTALL }
            .forEach { kind ->
                assertEquals(
                    PluginInstallTracking.Outcome.IGNORE,
                    PluginInstallTracking.outcomeOf(kind, ServerTaskStatus.DONE),
                    "$kind should not settle a plugin row"
                )
            }
    }

    @Test
    fun `a row whose jar is gone is stale`() {
        assertTrue(PluginInstallTracking.isStale("EssentialsX-2.21.2.jar", null, setOf("WorldEdit.jar")))
    }

    @Test
    fun `a row whose jar is there is kept`() {
        assertFalse(
            PluginInstallTracking.isStale(
                "EssentialsX-2.21.2.jar",
                null,
                setOf("EssentialsX-2.21.2.jar", "WorldEdit.jar")
            )
        )
    }

    @Test
    fun `a row an install still owns is never stale`() {
        // The jar is *expected* not to be there yet; the download is what puts it there.
        assertFalse(PluginInstallTracking.isStale("EssentialsX-2.22.0.jar", "task-uuid", emptySet()))
    }

    @Test
    fun `a directory Pano could not read is not evidence that anything is gone`() {
        // The worst possible outcome: a node blinking once and wiping every plugin's provenance.
        assertFalse(PluginInstallTracking.isStale("EssentialsX-2.21.2.jar", null, null))
    }

    @Test
    fun `an empty directory really is empty`() {
        assertTrue(PluginInstallTracking.isStale("EssentialsX-2.21.2.jar", null, emptySet()))
    }
}
