package com.panomc.platform.server.software

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerSoftwareUrlsTest {
    @Test
    fun `builds a paper project url on the fill v3 api`() {
        assertEquals(
            "https://fill.papermc.io/v3/projects/paper",
            ServerSoftwareUrls.paperProject("paper")
        )
    }

    @Test
    fun `builds a paper builds url on the fill v3 api`() {
        assertEquals(
            "https://fill.papermc.io/v3/projects/velocity/versions/3.5.1/builds",
            ServerSoftwareUrls.paperBuilds("velocity", "3.5.1")
        )
    }

    @Test
    fun `accepts a download url published by papermc`() {
        assertTrue(
            ServerSoftwareUrls.isPaperDownload(
                "https://fill-data.papermc.io/v1/objects/8de7c52c/paper-1.21.8-60.jar"
            )
        )
        assertTrue(ServerSoftwareUrls.isPaperDownload("https://api.papermc.io/v2/projects/paper/x.jar"))
    }

    @Test
    fun `refuses a download url that is not papermc's`() {
        assertFalse(ServerSoftwareUrls.isPaperDownload("https://evil.example.com/paper.jar"))
        assertFalse(ServerSoftwareUrls.isPaperDownload("http://fill-data.papermc.io/paper.jar"))
        assertFalse(ServerSoftwareUrls.isPaperDownload("https://papermc.io.evil.example.com/paper.jar"))
        assertFalse(ServerSoftwareUrls.isPaperDownload("not a url at all"))
        assertFalse(ServerSoftwareUrls.isPaperDownload(null))
    }

    @Test
    fun `builds purpur urls`() {
        assertEquals("https://api.purpurmc.org/v2/purpur", ServerSoftwareUrls.purpurVersions())
        assertEquals("https://api.purpurmc.org/v2/purpur/1.21.4", ServerSoftwareUrls.purpurVersion("1.21.4"))
        assertEquals(
            "https://api.purpurmc.org/v2/purpur/1.21.4/2345/download",
            ServerSoftwareUrls.purpurDownload("1.21.4", "2345")
        )
    }

    @Test
    fun `builds fabric urls`() {
        assertEquals("https://meta.fabricmc.net/v2/versions/game", ServerSoftwareUrls.fabricGameVersions())
        assertEquals("https://meta.fabricmc.net/v2/versions/installer", ServerSoftwareUrls.fabricInstallerVersions())
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.21.4",
            ServerSoftwareUrls.fabricLoaderVersions("1.21.4")
        )
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.21.4/0.16.9/1.0.1/server/jar",
            ServerSoftwareUrls.fabricServerJar("1.21.4", "0.16.9", "1.0.1")
        )
    }

    @Test
    fun `accepts the segments real versions look like`() {
        listOf("1.21.4", "1.20.1-rc1", "24w45a", "3.4.0-SNAPSHOT", "0.16.9", "paper-1.21.4-232.jar")
            .forEach { assertTrue(ServerSoftwareUrls.isSafeSegment(it), "should accept $it") }
    }

    @Test
    fun `refuses a segment that would escape the path`() {
        listOf("..", "../../etc", "1.21.4/../../x", "a b", "a?b=1", "a%2e%2e", "a#b", "", null)
            .forEach { assertFalse(ServerSoftwareUrls.isSafeSegment(it), "should refuse $it") }
    }

    @Test
    fun `refuses an absurdly long segment`() {
        assertFalse(ServerSoftwareUrls.isSafeSegment("1".repeat(200)))
    }

    @Test
    fun `builders return null instead of an unsafe url`() {
        assertNull(ServerSoftwareUrls.paperProject("../admin"))
        assertNull(ServerSoftwareUrls.paperBuilds("paper", "../../projects"))
        assertNull(ServerSoftwareUrls.paperBuilds("../../projects", "1.21.8"))
        assertNull(ServerSoftwareUrls.purpurVersion("../"))
        assertNull(ServerSoftwareUrls.purpurDownload("1.21.4", "a/b"))
        assertNull(ServerSoftwareUrls.fabricLoaderVersions("1.21.4/evil"))
        assertNull(ServerSoftwareUrls.fabricServerJar("1.21.4", "0.16.9", "../installer"))
    }

    @Test
    fun `builds the spigot hub urls the catalog fetches`() {
        assertEquals("https://hub.spigotmc.org/versions/1.21.8.json", ServerSoftwareUrls.spigotVersion("1.21.8"))
        assertNull(ServerSoftwareUrls.spigotVersion("../../jenkins"))
    }

    @Test
    fun `encodes the jenkins tree query so a web client accepts it`() {
        val builds = ServerSoftwareUrls.bungeeCordBuilds(20)

        // Brackets and braces are what Jenkins' tree parameter is written in and what a URL
        // parser refuses raw; sending them unescaped is how this endpoint stops answering.
        assertEquals(
            "https://ci.md-5.net/job/BungeeCord/api/json?tree=builds%5Bnumber,result%5D%7B0,20%7D",
            builds
        )
        assertFalse(builds.contains("["))
        assertFalse(builds.contains("{"))
    }

    @Test
    fun `builds one bungeecord build's api and download urls`() {
        assertEquals(
            "https://ci.md-5.net/job/BungeeCord/2096/artifact/bootstrap/target/BungeeCord.jar",
            ServerSoftwareUrls.bungeeCordDownload("2096")
        )
        assertTrue(ServerSoftwareUrls.bungeeCordBuild("lastSuccessfulBuild")!!.contains("fingerprint%5B"))
        assertNull(ServerSoftwareUrls.bungeeCordDownload("../../../etc"))
        assertNull(ServerSoftwareUrls.bungeeCordBuild("a/b"))
    }

    @Test
    fun `latest is jenkins' last successful build and a number is itself`() {
        assertEquals("lastSuccessfulBuild", ServerSoftwareUrls.bungeeCordReference("latest"))
        assertEquals("2096", ServerSoftwareUrls.bungeeCordReference("2096"))
    }
}
