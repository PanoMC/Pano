package com.panomc.platform.route

import com.panomc.platform.api.SitemapEntry
import com.panomc.platform.api.SitemapProvider
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.PageRequest
import com.panomc.platform.route.api.GetSitemapAPI
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** PF-10: `GET /api/v1/sitemap` and the SitemapProvider hook of doc 04 section 8. */
class SitemapTest {
    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, _, _ -> throw UnsupportedOperationException() } as SqlClient

    private fun provider(vararg entries: SitemapEntry) = object : SitemapProvider {
        override suspend fun entries(sqlClient: SqlClient) = entries.toList()
    }

    private val failing = object : SitemapProvider {
        override suspend fun entries(sqlClient: SqlClient): List<SitemapEntry> = throw IllegalStateException("boom")
    }

    private fun post(url: String, at: Long = 1) = SitemapEntry("post", mapOf("url" to url), at)

    private fun collect(
        core: List<SitemapProvider>,
        plugins: Map<String, List<SitemapProvider>> = emptyMap()
    ) = runBlocking { GetSitemapAPI.collect(core, plugins, sqlClient) }

    @Suppress("UNCHECKED_CAST")
    private fun items(body: Map<String, Any?>) = body["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun pageOf(body: Map<String, Any?>) = body["page"] as Map<String, Any?>

    @Test
    fun `entries are type and params with updatedAt`() {
        val body = GetSitemapAPI.payload(collect(listOf(provider(post("hello", 1700000000000)))), PageRequest(1, 100))

        assertEquals(
            listOf(mapOf("type" to "post", "params" to mapOf("url" to "hello"), "updatedAt" to 1700000000000L)),
            items(body)
        )
        assertEquals(
            mapOf<String, Any>("number" to 1, "size" to 100, "totalItems" to 1L, "totalPages" to 1L),
            pageOf(body)
        )
    }

    @Test
    fun `updatedAt is left out when unknown`() {
        val body = GetSitemapAPI.payload(
            collect(listOf(provider(SitemapEntry("post", mapOf("url" to "x"))))), PageRequest(1, 100)
        )

        assertFalse(items(body)[0].containsKey("updatedAt"))
    }

    @Test
    fun `plugin entries need the plugin id as type prefix`() {
        val entries = collect(
            emptyList(),
            mapOf(
                "pano-plugin-market" to listOf(
                    provider(
                        SitemapEntry("pano-plugin-market:product", mapOf("slug" to "sword")),
                        SitemapEntry("pano-plugin-other:product", mapOf("slug" to "stolen")),
                        SitemapEntry("post", mapOf("url" to "spoof")),
                        SitemapEntry("pano-plugin-market:", mapOf("slug" to "empty"))
                    )
                )
            )
        )

        assertEquals(listOf("pano-plugin-market:product"), entries.map { it.type })
    }

    @Test
    fun `core entries use bare types`() {
        val entries = collect(listOf(provider(post("a"), SitemapEntry("x:y", mapOf()))))

        assertEquals(listOf("post"), entries.map { it.type })
    }

    @Test
    fun `a failing provider is skipped and the others still answer`() {
        val entries = collect(
            listOf(failing, provider(post("a"))),
            mapOf("pano-plugin-market" to listOf(failing, provider(SitemapEntry("pano-plugin-market:p", mapOf()))))
        )

        assertEquals(listOf("pano-plugin-market:p", "post"), entries.map { it.type })
    }

    @Test
    fun `entries are ordered by type then params so pages are stable`() {
        val entries = collect(listOf(provider(post("b"), post("a"), post("c"))))

        assertEquals(listOf("a", "b", "c"), entries.map { it.params["url"] })
    }

    @Test
    fun `entries are paged and a page past the end is not found`() {
        val entries = collect(listOf(provider(*(1..5).map { post("p$it") }.toTypedArray())))

        val second = GetSitemapAPI.payload(entries, PageRequest(2, 2))

        assertEquals(listOf("p3", "p4"), items(second).map { (it["params"] as Map<*, *>)["url"] })
        assertEquals(3L, pageOf(second)["totalPages"])
        assertEquals(1, items(GetSitemapAPI.payload(entries, PageRequest(3, 2))).size)
        assertThrows(PageNotFound::class.java) { GetSitemapAPI.payload(entries, PageRequest(4, 2)) }
    }

    @Test
    fun `an empty sitemap has zero pages`() {
        val body = GetSitemapAPI.payload(collect(emptyList()), PageRequest(1, 100))

        assertTrue(items(body).isEmpty())
        assertEquals(0L, pageOf(body)["totalPages"])
    }
}
