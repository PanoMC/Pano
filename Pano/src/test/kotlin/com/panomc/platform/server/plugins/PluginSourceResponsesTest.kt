package com.panomc.platform.server.plugins

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mappers, against payloads captured from the live APIs.
 *
 * Fixtures rather than live calls on purpose: this suite has to tell the difference between "our
 * mapping is wrong" and "Modrinth is having a bad minute", and only a frozen payload can.
 */
class PluginSourceResponsesTest {
    private fun jsonObject(name: String): JsonObject =
        JsonObject(readFixture(name))

    private fun jsonArray(name: String): JsonArray =
        JsonArray(readFixture(name))

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("plugin-sources/$name")!!
            .bufferedReader()
            .use { it.readText() }

    // ---------------------------------------------------------------------------------- Modrinth

    @Test
    fun `maps a modrinth search hit`() {
        val results = ModrinthResponses.searchResults(jsonObject("modrinth-search.json"))

        assertEquals(1, results.size)

        val hit = results.first()

        assertEquals("modrinth", hit.source)
        assertEquals("hXiIvTyT", hit.projectId)
        assertEquals("essentialsx", hit.slug)
        assertEquals("EssentialsX", hit.name)
        assertEquals("mdcfe", hit.author)
        assertEquals("https://modrinth.com/project/essentialsx", hit.pageUrl)
        assertTrue(hit.downloads > 0)
        assertTrue(hit.compatible)
    }

    @Test
    fun `reads modrinth pagination`() {
        val body = jsonObject("modrinth-search.json")

        assertTrue(ModrinthResponses.hasMore(body, 0, 1))
        assertFalse(ModrinthResponses.hasMore(JsonObject().put("total_hits", 2), 0, 2))
    }

    @Test
    fun `maps a modrinth version with its hashes`() {
        val versions = ModrinthResponses.versions(
            jsonArray("modrinth-versions.json"),
            loaders = listOf("paper", "spigot", "bukkit"),
            gameVersions = listOf("1.21.8", "1.21")
        )

        assertEquals(1, versions.size)

        val version = versions.first()

        assertEquals("Oa9ZDzZq", version.id)
        assertEquals("2.21.2", version.versionNumber)
        assertEquals("release", version.channel)
        assertTrue(version.compatible)

        val file = version.files.single()

        assertEquals("EssentialsX-2.21.2.jar", file.filename)
        assertTrue(file.primary)
        assertNotNull(file.sha512)
        assertNotNull(file.sha1)
        assertFalse(file.external)
    }

    @Test
    fun `marks a modrinth version for another loader incompatible`() {
        val versions = ModrinthResponses.versions(
            jsonArray("modrinth-versions.json"),
            loaders = listOf("fabric"),
            gameVersions = listOf("1.21.8")
        )

        assertFalse(versions.single().compatible)
    }

    // ------------------------------------------------------------------------------------ Hangar

    @Test
    fun `maps a hangar project`() {
        val results = HangarResponses.searchResults(
            jsonObject("hangar-projects.json"),
            platform = "PAPER",
            gameVersions = listOf("1.21.8", "1.21")
        )

        val hit = results.single()

        assertEquals("hangar", hit.source)
        assertEquals("Essentials", hit.projectId)
        assertEquals("EssentialsX", hit.author)
        assertEquals("https://hangar.papermc.io/EssentialsX/Essentials", hit.pageUrl)
        assertTrue(hit.downloads > 0)
    }

    @Test
    fun `reports a hangar project with nothing for this platform as incompatible`() {
        val results = HangarResponses.searchResults(
            jsonObject("hangar-projects.json"),
            platform = "VELOCITY",
            gameVersions = emptyList()
        )

        assertFalse(results.single().compatible)
    }

    @Test
    fun `separates hosted hangar downloads from external ones`() {
        val versions = HangarResponses.versions(
            jsonObject("hangar-versions.json"),
            platform = "PAPER",
            gameVersions = listOf("1.21.11")
        )

        assertEquals(2, versions.size)

        val external = versions.first { it.id == "2.22.0" }.files.single()

        assertTrue(external.external)
        assertEquals("https://github.com/EssentialsX/Essentials/releases/tag/2.22.0", external.url)
        assertNull(external.filename)

        val hosted = versions.first { it.id != "2.22.0" }.files.single()

        assertFalse(hosted.external)
        assertTrue(hosted.url!!.startsWith("https://hangarcdn.papermc.io/"))
        assertNotNull(hosted.sha256)
        assertTrue(hosted.size > 0)
    }

    // ------------------------------------------------------------------------------- CurseForge

    @Test
    fun `drops curseforge projects whose author forbade distribution`() {
        val results = CurseForgeResponses.searchResults(jsonObject("curseforge-search.json"))

        assertEquals(1, results.size)
        assertEquals("238222", results.single().projectId)
        assertEquals("mezz", results.single().author)
    }

    @Test
    fun `maps curseforge files with the sha1 hash and the release channel`() {
        val versions = CurseForgeResponses.versions(
            jsonObject("curseforge-files.json"),
            loaders = listOf("fabric"),
            gameVersions = listOf("1.21.1")
        )

        assertEquals(2, versions.size)

        val fabric = versions.first()

        assertEquals("release", fabric.channel)
        assertTrue(fabric.compatible)
        assertEquals("aa11bb22cc33", fabric.files.single().sha1)
        assertFalse(fabric.files.single().external)

        val forge = versions.last()

        assertEquals("beta", forge.channel)
        assertFalse(forge.compatible)
        // No downloadUrl means CurseForge will not hand the file over to a third party.
        assertTrue(forge.files.single().external)
    }

    // ---------------------------------------------------------------- Identification by hash

    @Test
    fun `maps a modrinth version_files answer keyed by the hash that was asked about`() {
        val matches = ModrinthResponses.versionFiles(jsonObject("modrinth-version-files.json"))

        assertEquals(2, matches.size)

        val essentials = matches.first { it.key == "0b8d0d1a1e6e1b1a6d7c3a4c9c0a9f0f0e2b3c4d" }

        assertEquals("hXiIvTyT", essentials.projectId)
        assertEquals("5ecaFcKz", essentials.versionId)
        assertEquals("2.21.2", essentials.versionNumber)
        assertEquals("2025-03-26T19:26:15.171883Z", essentials.publishedAt)

        // The second entry is a different project, so a hash must never be paired by position.
        val worldEdit = matches.first { it.key == "1f2e3d4c5b6a79880123456789abcdef01234567" }

        assertEquals("1u6JkXh5", worldEdit.projectId)
        assertEquals("Qt4lmnOP", worldEdit.versionId)
    }

    @Test
    fun `an empty modrinth version_files answer identifies nothing`() {
        assertTrue(ModrinthResponses.versionFiles(JsonObject()).isEmpty())
        assertTrue(ModrinthResponses.versionFiles(null).isEmpty())
    }

    @Test
    fun `maps modrinth projects into names and type-correct page links`() {
        val projects = ModrinthResponses.projects(jsonArray("modrinth-projects.json"))

        assertEquals(3, projects.size)

        val essentials = projects.first { it.projectId == "hXiIvTyT" }

        assertEquals("EssentialsX", essentials.name)
        assertEquals("https://modrinth.com/plugin/essentialsx", essentials.pageUrl)

        // A mod is not served under /plugin/, so the type decides the link.
        assertEquals("https://modrinth.com/mod/worldedit", projects.first { it.projectId == "1u6JkXh5" }.pageUrl)

        // No slug means no page to link to, which is not a reason to lose the name.
        val slugless = projects.first { it.projectId == "noSlugHere" }

        assertEquals("Slugless", slugless.name)
        assertNull(slugless.pageUrl)
    }

    @Test
    fun `maps curseforge fingerprint matches back onto the fingerprint that matched`() {
        val matches = CurseForgeResponses.fingerprintMatches(jsonObject("curseforge-fingerprints.json"))

        // The third exact match carries no fingerprint, so there is nothing to pair it with.
        assertEquals(2, matches.size)

        val jei = matches.first { it.key == "2788266382" }

        assertEquals("238222", jei.projectId)
        assertEquals("4726098", jei.versionId)
        assertEquals("JEI 1.21-19.21.0.247", jei.versionNumber)
        assertEquals("2024-07-21T13:14:15.16Z", jei.publishedAt)

        // The modId is used when the match itself does not repeat the project id.
        val other = matches.first { it.key == "1540447798" }

        assertEquals("310111", other.projectId)
        assertEquals("SomeMod-3.2.1.jar", other.versionNumber)
    }

    @Test
    fun `an answer curseforge did not recognise identifies nothing`() {
        assertTrue(CurseForgeResponses.fingerprintMatches(JsonObject()).isEmpty())
        assertTrue(CurseForgeResponses.fingerprintMatches(null).isEmpty())
    }

    @Test
    fun `maps curseforge mods into names and links`() {
        val mods = CurseForgeResponses.mods(jsonObject("curseforge-mods.json"))

        assertEquals(2, mods.size)

        val jei = mods.first { it.projectId == "238222" }

        assertEquals("Just Enough Items", jei.name)
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/jei", jei.pageUrl)

        // A mod with no website link still has a name, which is the half that matters.
        val other = mods.first { it.projectId == "310111" }

        assertEquals("Some Mod", other.name)
        assertNull(other.pageUrl)
    }
}
