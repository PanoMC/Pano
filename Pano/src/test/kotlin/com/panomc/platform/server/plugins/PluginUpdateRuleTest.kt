package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.dto.PluginVersionData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that decides whether somebody is offered a new build of a plugin holding their server
 * together. Both ways of getting it wrong are silent in production: too eager pushes a pre-release
 * onto a stable server, too shy never updates anything and looks exactly like "there are no
 * updates".
 */
class PluginUpdateRuleTest {
    private fun version(
        id: String,
        channel: String?,
        publishedAt: String?,
        compatible: Boolean = true
    ) = PluginVersionData(
        id = id,
        name = "Plugin $id",
        versionNumber = id,
        gameVersions = listOf("1.21.4"),
        loaders = listOf("paper"),
        publishedAt = publishedAt,
        channel = channel,
        compatible = compatible,
        files = emptyList()
    )

    private val stable = version("2.0.0", "release", "2025-03-01T00:00:00Z")
    private val older = version("1.9.0", "release", "2024-12-01T00:00:00Z")
    private val beta = version("2.1.0-beta", "beta", "2025-04-01T00:00:00Z")
    private val alpha = version("2.2.0-alpha", "alpha", "2025-05-01T00:00:00Z")

    @Test
    fun `a server on a stable build is only offered stable builds`() {
        val candidates = PluginUpdateRule.candidates(listOf(alpha, beta, stable, older), "release")

        assertEquals(listOf("2.0.0", "1.9.0"), candidates.map { it.id })
        assertEquals("2.0.0", PluginUpdateRule.latest(listOf(alpha, beta, stable, older), "release")?.id)
    }

    @Test
    fun `a server deliberately on a beta keeps being offered betas`() {
        val latest = PluginUpdateRule.latest(listOf(alpha, beta, stable, older), "beta")

        assertEquals("2.1.0-beta", latest?.id)

        // …but not the alpha, which is a channel nobody opted into.
        assertFalse(PluginUpdateRule.candidates(listOf(alpha, beta, stable), "beta").any { it.id == alpha.id })
    }

    @Test
    fun `an incompatible version is never a candidate whatever its channel`() {
        val incompatible = version("3.0.0", "release", "2026-01-01T00:00:00Z", compatible = false)

        assertEquals("2.0.0", PluginUpdateRule.latest(listOf(incompatible, stable), "release")?.id)
    }

    @Test
    fun `a version with no channel counts as stable`() {
        // Hangar lets an author name their own channel, and an unreadable one must not mean this
        // plugin can never be updated again.
        val unnamed = version("2.5.0", null, "2025-06-01T00:00:00Z")

        assertEquals("2.5.0", PluginUpdateRule.latest(listOf(unnamed, stable), "release")?.id)
    }

    @Test
    fun `the newest is decided by publication date and not by list order`() {
        // Deliberately out of order, and with version numbers that sort the other way.
        val latest = PluginUpdateRule.latest(listOf(older, stable), "release")

        assertEquals("2.0.0", latest?.id)
    }

    @Test
    fun `falls back to the source's own order when nothing carries a date`() {
        val first = version("b", "release", null)
        val second = version("a", "release", "   ")

        assertEquals("b", PluginUpdateRule.latest(listOf(first, second), "release")?.id)
    }

    @Test
    fun `no candidate means no latest`() {
        assertNull(PluginUpdateRule.latest(emptyList(), "release"))
        assertNull(PluginUpdateRule.latest(listOf(version("x", "release", null, compatible = false)), "release"))
    }

    @Test
    fun `the installed version is not an update of itself`() {
        assertFalse(PluginUpdateRule.isUpdateAvailable("2.0.0", stable.publishedAt, stable))
    }

    @Test
    fun `a newer build is an update`() {
        assertTrue(PluginUpdateRule.isUpdateAvailable("1.9.0", older.publishedAt, stable))
    }

    @Test
    fun `a differently identified but older build is not an update`() {
        // A source reordering its list, or an author pulling a release, must not walk a server
        // backwards.
        assertFalse(PluginUpdateRule.isUpdateAvailable("2.0.0", stable.publishedAt, older))
    }

    @Test
    fun `a republished build with the same date is not an update`() {
        val republished = version("2.0.1", "release", stable.publishedAt)

        assertFalse(PluginUpdateRule.isUpdateAvailable("2.0.0", stable.publishedAt, republished))
    }

    @Test
    fun `an installed version with no recorded date trusts the source`() {
        // Rows from before dates were recorded would otherwise never be updated again.
        assertTrue(PluginUpdateRule.isUpdateAvailable("1.9.0", null, stable))
    }

    @Test
    fun `nothing to compare against is not an update`() {
        assertFalse(PluginUpdateRule.isUpdateAvailable("1.9.0", older.publishedAt, null))

        // The candidate's own date being unreadable is a reason to say nothing, not to update.
        assertFalse(PluginUpdateRule.isUpdateAvailable("1.9.0", older.publishedAt, version("3.0", "release", null)))
    }

    @Test
    fun `reads the timestamps all three sources publish`() {
        // Modrinth, with microseconds; CurseForge, with two decimals; Hangar, without an offset.
        assertEquals(
            PluginUpdateRule.timestampOf("2025-03-26T19:26:15Z"),
            PluginUpdateRule.timestampOf("2025-03-26T19:26:15.000000Z")
        )

        assertTrue(
            PluginUpdateRule.timestampOf("2024-07-21T13:14:15.16Z")!!
                .isBefore(PluginUpdateRule.timestampOf("2025-03-26T19:26:15.171883Z"))
        )

        assertEquals(
            PluginUpdateRule.timestampOf("2025-03-26T19:26:15Z"),
            PluginUpdateRule.timestampOf("2025-03-26T19:26:15")
        )

        assertNull(PluginUpdateRule.timestampOf("last tuesday"))
        assertNull(PluginUpdateRule.timestampOf(null))
        assertNull(PluginUpdateRule.timestampOf(" "))
    }
}
