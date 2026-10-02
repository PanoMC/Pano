package com.panomc.platform.update

import com.panomc.platform.ReleaseStage
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.util.UpdateSource
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Source order, fallbacks and the last-good store against a fake Pano API + fake GitHub API. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseLookupTest {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val webClient = WebClient.create(vertx)
    private lateinit var server: HttpServer
    private var port = 0

    /** Status + body the fake Pano API answers with, per channel; null status = 200 with the body. */
    @Volatile
    private var apiStatus = 200

    @Volatile
    private var apiBody: (String) -> String = { channel -> apiAnswer(channel, "v1.0.0-$channel.5".takeIf { channel != "stable" } ?: "v1.0.0") }

    @Volatile
    private var githubStatus = 200

    private val calls = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun start(): Unit = runBlocking {
        val router = Router.router(vertx)

        router.get("/api/releases/:product").handler { ctx ->
            calls += "api:${ctx.pathParam("product")}:${ctx.queryParams()["channel"]}"
            ctx.response().setStatusCode(apiStatus).end(apiBody(ctx.queryParams()["channel"]))
        }

        router.get("/gh/repos/PanoMC/:repo/releases/latest").handler { ctx ->
            calls += "github:latest"
            if (githubStatus != 200) {
                ctx.response().setStatusCode(githubStatus).end()
                return@handler
            }
            ctx.response().setStatusCode(404).end()
        }

        router.get("/gh/repos/PanoMC/:repo/releases").handler { ctx ->
            calls += "github:list"
            if (githubStatus != 200) {
                ctx.response().setStatusCode(githubStatus).end()
                return@handler
            }
            ctx.response().end(
                JsonArray()
                    .add(githubRelease("v1.0.0-beta.9", draft = true))
                    .add(githubRelease("v1.0.0-beta.3"))
                    .add(githubRelease("v1.0.0-alpha.12"))
                    .add(githubRelease("v0.9.0"))
                    .add(githubRelease("v1.0.0-alpha.11"))
                    .encode()
            )
        }

        server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").coAwait()
        port = server.actualPort()
    }

    @AfterAll
    fun stop() {
        server.close()
        vertx.close()
    }

    @BeforeEach
    fun reset() {
        apiStatus = 200
        apiBody = { channel -> apiAnswer(channel, if (channel == "stable") "v1.0.0" else "v1.0.0-$channel.5") }
        githubStatus = 200
        calls.clear()
    }

    private fun lookup(
        source: UpdateSource,
        storeFile: File = File(temp, "release-info-${System.nanoTime()}.json"),
        apiUrl: String = "http://127.0.0.1:$port/api"
    ) = ReleaseLookup(
        webClient = webClient,
        logger = LoggerFactory.getLogger("ReleaseLookupTest"),
        store = LastGoodReleaseStore(storeFile),
        panoApiUrl = { apiUrl },
        updateSource = { source },
        githubApiBase = "http://127.0.0.1:$port/gh"
    )

    @Test
    fun `AUTO answers from the Pano API and never asks GitHub when it works`(): Unit = runBlocking {
        val list = lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)

        assertEquals(ReleaseOrigin.PANO_API, list.origin)
        assertEquals("v1.0.0-alpha.5", list.latest?.tag)
        assertEquals("1.0.0-alpha.5", list.latest?.version)
        assertEquals(listOf("api:pano:alpha"), calls)
    }

    @Test
    fun `AUTO falls back to GitHub when the Pano API answers 503`(): Unit = runBlocking {
        apiStatus = 503
        apiBody = { JsonObject().put("result", "error").put("error", "NOT_READY").encode() }

        val list = lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)

        assertEquals(ReleaseOrigin.GITHUB, list.origin)
        // Today's channel semantics: the newest release of exactly the alpha type, drafts skipped.
        assertEquals("v1.0.0-alpha.12", list.latest?.tag)
        assertEquals(listOf("api:pano:alpha", "github:list"), calls)
    }

    @Test
    fun `AUTO falls back to GitHub on a malformed body or an unusable tag`(): Unit = runBlocking {
        apiBody = { "<html>oops</html>" }
        assertEquals(ReleaseOrigin.GITHUB, lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.BETA).origin)

        apiBody = { channel -> apiAnswer(channel, "../../evil") }
        val list = lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.BETA)
        assertEquals(ReleaseOrigin.GITHUB, list.origin)
        assertEquals("v1.0.0-beta.3", list.latest?.tag)

        // An answer for another channel than asked is malformed too.
        apiBody = { apiAnswer("alpha", "v1.0.0-alpha.5") }
        assertEquals(ReleaseOrigin.GITHUB, lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.BETA).origin)
    }

    @Test
    fun `AUTO falls back to GitHub when the Pano API is unreachable`(): Unit = runBlocking {
        val list = lookup(UpdateSource.AUTO, apiUrl = "http://127.0.0.1:1/api").lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)

        assertEquals(ReleaseOrigin.GITHUB, list.origin)
    }

    @Test
    fun `both sources down answers the last good result, and nothing stored throws`(): Unit = runBlocking {
        val storeFile = File(temp, "release-info.json")

        lookup(UpdateSource.AUTO, storeFile).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)
        assertTrue(storeFile.isFile)

        apiStatus = 500
        githubStatus = 403

        // A fresh instance (as after a restart) reads it back from disk.
        val list = lookup(UpdateSource.AUTO, storeFile).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)
        assertEquals(ReleaseOrigin.LAST_GOOD, list.origin)
        assertEquals("v1.0.0-alpha.5", list.latest?.tag)

        assertThrows(ReleaseLookupFailed::class.java) {
            runBlocking { lookup(UpdateSource.AUTO, storeFile).lookup(ReleaseProduct.PANO, ReleaseStage.BETA) }
        }
    }

    @Test
    fun `PANO_API never asks GitHub`(): Unit = runBlocking {
        apiStatus = 503

        assertThrows(ReleaseLookupFailed::class.java) {
            runBlocking { lookup(UpdateSource.PANO_API).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA) }
        }
        assertEquals(listOf("api:pano:alpha"), calls)
    }

    @Test
    fun `GITHUB never asks the Pano API`(): Unit = runBlocking {
        val list = lookup(UpdateSource.GITHUB).lookup(ReleaseProduct.PANO, ReleaseStage.BETA)

        assertEquals(ReleaseOrigin.GITHUB, list.origin)
        assertEquals("v1.0.0-beta.3", list.latest?.tag)
        assertEquals(listOf("github:list"), calls)
    }

    @Test
    fun `the stable channel is asked as stable, and GitHub's missing latest release is no release`(): Unit = runBlocking {
        val fromApi = lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO, ReleaseStage.RELEASE)
        assertEquals("v1.0.0", fromApi.latest?.tag)
        assertEquals(listOf("api:pano:stable"), calls)

        calls.clear()
        val fromGitHub = lookup(UpdateSource.GITHUB).lookup(ReleaseProduct.PANO, ReleaseStage.RELEASE)
        assertNull(fromGitHub.latest)
        assertTrue(fromGitHub.releases.isEmpty())
        assertEquals(listOf("github:latest"), calls)
    }

    @Test
    fun `GitHub channel membership matches the API's`(): Unit = runBlocking {
        val alpha = lookup(UpdateSource.GITHUB).lookup(ReleaseProduct.PANO, ReleaseStage.ALPHA)
        assertEquals(listOf("v1.0.0-alpha.12", "v0.9.0", "v1.0.0-alpha.11"), alpha.releases.map { it.tag })

        val beta = lookup(UpdateSource.GITHUB).lookup(ReleaseProduct.PANO, ReleaseStage.BETA)
        assertEquals(listOf("v1.0.0-beta.3", "v0.9.0"), beta.releases.map { it.tag })

        assertTrue(ReleaseLookup.inChannel("v1.0.0", ReleaseStage.ALPHA))
        assertTrue(ReleaseLookup.inChannel("v1.0.0", ReleaseStage.RELEASE))
        assertTrue(ReleaseLookup.inChannel("v1.0.0-alpha.1", ReleaseStage.ALPHA))
        assertTrue(!ReleaseLookup.inChannel("v1.0.0-beta.1", ReleaseStage.ALPHA))
        assertTrue(!ReleaseLookup.inChannel("v1.0.0-alpha.1", ReleaseStage.BETA))
        assertTrue(!ReleaseLookup.inChannel("v1.0.0-beta.1", ReleaseStage.RELEASE))
    }

    @Test
    fun `channel mapping`() {
        assertEquals("alpha", ReleaseLookup.apiChannel(ReleaseStage.ALPHA))
        assertEquals("beta", ReleaseLookup.apiChannel(ReleaseStage.BETA))
        assertEquals("stable", ReleaseLookup.apiChannel(ReleaseStage.RELEASE))
        assertEquals("pano:stable", ReleaseLookup.storeKey(ReleaseProduct.PANO, ReleaseStage.RELEASE))
        assertEquals("pano-mc-plugin:all", ReleaseLookup.storeKey(ReleaseProduct.PANO_MC_PLUGIN, null))
    }

    @Test
    fun `source order per update-source value`() {
        assertEquals(listOf(ReleaseOrigin.PANO_API, ReleaseOrigin.GITHUB), ReleaseLookup.originsFor(UpdateSource.AUTO))
        assertEquals(listOf(ReleaseOrigin.PANO_API), ReleaseLookup.originsFor(UpdateSource.PANO_API))
        assertEquals(listOf(ReleaseOrigin.GITHUB), ReleaseLookup.originsFor(UpdateSource.GITHUB))
    }

    @Test
    fun `every channel merges the alpha and beta answers newest first for the plugin`(): Unit = runBlocking {
        val list = lookup(UpdateSource.AUTO).lookup(ReleaseProduct.PANO_MC_PLUGIN, null)

        assertEquals(ReleaseOrigin.PANO_API, list.origin)
        assertEquals(listOf("v1.0.0-beta.5", "v1.0.0-alpha.5"), list.releases.map { it.tag })
        assertEquals(setOf("api:pano-mc-plugin:alpha", "api:pano-mc-plugin:beta"), calls.toSet())
    }

    @Test
    fun `plugin jars are named after the tag when the API answered, and taken from GitHub's assets otherwise`() {
        val fromApi = ReleaseInfo("1.0.0-alpha.65", "v1.0.0-alpha.65", true, null, null, null)
        val assets = ManagedPluginJarResolver.newestAssets(listOf(fromApi))

        assertEquals(
            "https://github.com/PanoMC/pano-mc-plugin/releases/download/v1.0.0-alpha.65/pano-spigot-1.0.0-alpha.65.jar",
            assets.assets["spigot"]
        )
        assertEquals(ManagedPluginJarResolver.PLATFORMS.toSet(), assets.assets.keys)
        assertEquals("1.0.0-alpha.65", assets.versions["fabric"])

        // GitHub: a release without plugin jars (still uploading) is skipped.
        val uploading = ReleaseInfo("1.0.0-alpha.66", "v1.0.0-alpha.66", true, null, null, null, listOf(ReleaseAsset("LICENSE", 1, null)))
        val done = ReleaseInfo(
            "1.0.0-alpha.65", "v1.0.0-alpha.65", true, null, null, null,
            listOf(ReleaseAsset("pano-velocity-1.0.0-alpha.65.jar", 1, null))
        )
        val fromGitHub = ManagedPluginJarResolver.newestAssets(listOf(uploading, done))

        assertEquals(setOf("velocity"), fromGitHub.assets.keys)
        assertEquals("1.0.0-alpha.65", fromGitHub.versions["velocity"])
    }

    @Test
    fun `parses the API envelope`() {
        val list = ReleaseLookup.parsePanoApiBody(apiAnswer("beta", "v2.1.0-beta.1"), ReleaseStage.BETA, 42L)

        assertEquals("v2.1.0-beta.1", list.latest?.tag)
        assertEquals(1_700_000_000_000L, list.latest?.publishedAt)
        assertEquals("notes", list.latest?.notes)
        assertEquals(42L, list.fetchedAt)

        val none = ReleaseLookup.parsePanoApiBody(
            JsonObject().put("result", "ok").put(
                "data", JsonObject().put("channel", "stable").putNull("latest").put("releases", JsonArray())
            ).encode(),
            ReleaseStage.RELEASE,
            0L
        )
        assertNull(none.latest)

        assertThrows(IllegalStateException::class.java) {
            ReleaseLookup.parsePanoApiBody(JsonObject().put("result", "ok").encode(), ReleaseStage.BETA, 0L)
        }
        // The fields at the top level instead of in the envelope's data is not the contract.
        assertThrows(IllegalStateException::class.java) {
            ReleaseLookup.parsePanoApiBody(
                JsonObject().put("result", "ok").put("channel", "beta").put("releases", JsonArray()).encode(),
                ReleaseStage.BETA,
                0L
            )
        }
    }

    private fun apiAnswer(channel: String, tag: String): String {
        val release = JsonObject()
            .put("version", tag.removePrefix("v"))
            .put("tag", tag)
            .put("prerelease", channel != "stable")
            .put("publishedAt", 1_700_000_000_000L)
            .put("url", "https://github.com/PanoMC/Pano/releases/tag/$tag")
            .put("notes", "notes")

        return JsonObject()
            .put("result", "ok")
            .put(
                "data", JsonObject()
                    .put("channel", channel)
                    .put("latest", release)
                    .put("releases", JsonArray().add(release))
            )
            .encode()
    }

    private fun githubRelease(tag: String, draft: Boolean = false) = JsonObject()
        .put("tag_name", tag)
        .put("draft", draft)
        .put("prerelease", true)
        .put("published_at", "2026-10-02T14:09:49Z")
        .put("body", "changelog")
        .put("assets", JsonArray().add(JsonObject().put("name", "Pano-${tag.removePrefix("v")}.jar").put("size", 10).put("digest", "sha256:ab")))
}
