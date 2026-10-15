package com.panomc.platform.schema

import com.panomc.platform.error.NotExists
import com.panomc.platform.route.ApiPathRefusal
import com.panomc.platform.route.EndpointDocRefusal
import com.panomc.platform.route.Mount
import com.panomc.platform.route.Namespace
import com.panomc.platform.route.RouteEntry
import com.panomc.platform.route.RouteTable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Doc 04 sections 5 and 6: endpoint docs, stability, deprecation and what the route table records. */
class EndpointDocTest {
    private val post = objectSchema().requiredProperty("id", intSchema()).requiredProperty("title", stringSchema())

    private fun entry(
        path: String = "/posts",
        pluginId: String? = null,
        mount: Mount = Mount.API,
        namespace: Namespace = Namespace.SITE,
        doc: EndpointDoc? = null,
        stability: Stability? = null,
        deprecation: Deprecation? = null
    ) = RouteEntry(
        method = "GET",
        path = path,
        declared = path,
        pluginId = pluginId,
        routeClass = EndpointDocTest::class.java,
        mount = mount,
        namespace = namespace,
        doc = doc,
        declaredStability = stability,
        deprecation = deprecation
    )

    // stability

    @Test
    fun `site routes are public and the panel namespace is internal`() {
        assertEquals(Stability.PUBLIC, entry("/posts").stability)
        assertEquals(Stability.INTERNAL, entry("/settings", namespace = Namespace.PANEL).stability)
        assertEquals(Stability.INTERNAL, entry("/products", "pano-plugin-market", namespace = Namespace.PANEL).stability)
    }

    @Test
    fun `core paths under setup node server and maintenance are internal`() {
        listOf("/setup/finish", "/node/transfer/x", "/server/connect", "/maintenance/status").forEach {
            assertEquals(Stability.INTERNAL, entry(it).stability, it)
        }

        // a segment that merely starts the same way is a public route
        assertEquals(Stability.PUBLIC, entry("/servers").stability)
        assertEquals(Stability.PUBLIC, entry("/nodes").stability)
    }

    @Test
    fun `a plugin keeps its own server path public`() {
        assertEquals(Stability.PUBLIC, entry("/server/status", "pano-plugin-market").stability)
    }

    @Test
    fun `a route outside the API mount is internal`() {
        assertEquals(Stability.INTERNAL, entry("/panel/host-sso", mount = Mount.ROOT).stability)
    }

    @Test
    fun `a declared stability overrides the derived one`() {
        assertEquals(Stability.PUBLIC, entry("/server/icon/default", stability = Stability.PUBLIC).stability)
        assertEquals(Stability.INTERNAL, entry("/posts", stability = Stability.INTERNAL).stability)
    }

    // route table records the fields

    @Test
    fun `the route table keeps doc and deprecation on the entry`() {
        val doc = EndpointDoc("Reads a post", response = post)
        val deprecation = Deprecation(3, "2027-06-01", "GET /posts")
        val table = RouteTable()

        table.register(listOf(entry(doc = doc, deprecation = deprecation)))

        val found = table.find("GET", "/posts")

        assertNotNull(found)
        assertEquals(doc, found!!.doc)
        assertEquals(deprecation, found.deprecation)
        assertNull(entry().doc)
    }

    @Test
    fun `the route table refuses a response that is not an object`() {
        val table = RouteTable()
        val refusal = assertThrows(EndpointDocRefusal::class.java) {
            table.register(listOf(entry(doc = EndpointDoc("x", response = arraySchema().items(stringSchema())))))
        }

        assertTrue(refusal is ApiPathRefusal)
        assertTrue(refusal.message!!.contains("response must be an object schema"), refusal.message)
        assertTrue(refusal.message!!.contains(EndpointDocTest::class.java.name), refusal.message)
        assertTrue(table.entries().isEmpty())
    }

    @Test
    fun `the route table refuses a response declaring error or result`() {
        listOf("error", "result").forEach { key ->
            val table = RouteTable()
            val refusal = assertThrows(EndpointDocRefusal::class.java) {
                table.register(listOf(entry(doc = EndpointDoc("x", response = objectSchema().optionalProperty(key, stringSchema())))))
            }

            assertTrue(refusal.message!!.contains("\"$key\""), refusal.message)
        }
    }

    @Test
    fun `a list item is held to the same rules and response and paginatedItem are exclusive`() {
        assertNull(EndpointDoc("list", paginatedItem = post).problem())
        assertTrue(EndpointDoc("list", paginatedItem = stringSchema()).problem()!!.startsWith("paginatedItem must be an object"))
        assertTrue(EndpointDoc("both", response = post, paginatedItem = post).problem()!!.contains("both"))
        assertNull(EndpointDoc("nothing declared").problem())
    }

    // dev-mode response check

    @Test
    fun `check passes a matching body and names the failing pointer of a wrong one`() {
        val doc = EndpointDoc("Reads a post", response = post)

        assertTrue(doc.check(JsonObject().put("id", 1).put("title", "t")).isEmpty())

        val failures = doc.check(JsonObject().put("id", "one").put("title", "t"))

        assertTrue(failures.any { it.startsWith("/id") }, failures.toString())
        assertTrue(doc.check(JsonObject().put("title", "t")).isNotEmpty())
    }

    @Test
    fun `check of a list wraps the item in items and page`() {
        val doc = EndpointDoc("List", paginatedItem = post)
        val item = JsonObject().put("id", 1).put("title", "t")
        val page = JsonObject().put("number", 1).put("size", 10).put("totalItems", 1).put("totalPages", 1)

        assertTrue(doc.check(JsonObject().put("items", JsonArray().add(item)).put("page", page)).isEmpty())
        assertTrue(doc.check(JsonObject().put("items", JsonArray().add(JsonObject().put("id", 1))).put("page", page)).isNotEmpty())
        assertTrue(doc.check(JsonObject().put("items", JsonArray())).isNotEmpty())
    }

    @Test
    fun `check of a doc without a schema or a binary doc is silent`() {
        assertTrue(EndpointDoc("nothing").check(JsonObject().put("a", 1)).isEmpty())
        assertTrue(EndpointDoc("file", response = post, binary = true).check(JsonObject()).isEmpty())
        assertNull(EndpointDoc("file", response = post, binary = true).responseSchema)
    }

    // deprecation

    @Test
    fun `deprecation turns the removal date into a Sunset http date`() {
        val deprecation = Deprecation(sinceLevel = 3, removeAfter = "2027-06-01", replacement = "GET /posts")

        assertEquals("Tue, 01 Jun 2027 00:00:00 GMT", deprecation.sunset)
        assertEquals(3, deprecation.sinceLevel)
        assertEquals("GET /posts", deprecation.replacement)
    }

    @Test
    fun `deprecation refuses a date that is not ISO`() {
        assertThrows(IllegalArgumentException::class.java) { Deprecation(1, "June 2027") }
    }

    @Test
    fun `an error class can be listed in a doc`() {
        val doc = EndpointDoc("x", errors = listOf(NotExists::class))

        assertEquals(listOf(NotExists::class), doc.errors)
    }
}
