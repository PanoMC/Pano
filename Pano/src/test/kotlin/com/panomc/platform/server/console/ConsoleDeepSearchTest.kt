package com.panomc.platform.server.console

import com.google.gson.Gson
import com.panomc.platform.error.BadCursor
import com.panomc.platform.error.BadQuery
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.ReadFailed
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.event.request.ConsoleSearchResultEventRequest
import com.panomc.platform.server.message.ConsoleSearchMessage
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pano's relay of the console deep search: what it lets through to a source, and what it lets
 * through from one.
 */
class ConsoleDeepSearchTest {
    @Test
    fun `limit defaults and is clamped to what a source honours`() {
        assertEquals(ConsoleDeepSearch.DEFAULT_LIMIT, ConsoleDeepSearch.limit(null))
        assertEquals(1, ConsoleDeepSearch.limit(0))
        assertEquals(1, ConsoleDeepSearch.limit(-5))
        assertEquals(1000, ConsoleDeepSearch.limit(50_000))
        assertEquals(37, ConsoleDeepSearch.limit(37))
    }

    @Test
    fun `blank query is BAD_QUERY and a real one is trimmed and capped`() {
        assertThrows(BadQuery::class.java) { ConsoleDeepSearch.query(null) }
        assertThrows(BadQuery::class.java) { ConsoleDeepSearch.query("   ") }
        assertEquals("joined", ConsoleDeepSearch.query("  joined "))
        assertEquals(ConsoleSearch.MAX_QUERY_LENGTH, ConsoleDeepSearch.query("x".repeat(500)).length)
    }

    @Test
    fun `a cursor is base64url or nothing`() {
        assertNull(ConsoleDeepSearch.cursor(null))
        assertNull(ConsoleDeepSearch.cursor(""))
        assertEquals("eyJ2IjoxfQ", ConsoleDeepSearch.cursor("eyJ2IjoxfQ"))
        assertEquals("eyJ2IjoxfQ==", ConsoleDeepSearch.cursor("eyJ2IjoxfQ=="))

        assertThrows(BadCursor::class.java) { ConsoleDeepSearch.cursor("../etc/passwd") }
        assertThrows(BadCursor::class.java) { ConsoleDeepSearch.cursor("a b") }
        assertThrows(BadCursor::class.java) { ConsoleDeepSearch.cursor("abc+/") }
        assertThrows(BadCursor::class.java) { ConsoleDeepSearch.cursor("a".repeat(ConsoleDeepSearch.MAX_CURSOR_LENGTH + 1)) }
    }

    @Test
    fun `a node page is sanitised, keeps f and is labelled as the node`() {
        val payload = JsonObject()
            .put("ok", true)
            .put(
                "lines",
                JsonArray()
                    .add(JsonObject().put("t", 5).put("l", "warn").put("m", "hit").put("f", "latest.log").put("src", "plugin"))
                    .add(JsonObject().put("t", -1).put("l", "NOPE").put("m", "hit two").put("f", "a\u0000b.log.gz"))
            )
            .put("cursor", "eyJ2IjoxfQ")
            .put("done", false)
            .put("scannedFiles", 1)
            .put("totalFiles", 4)
            .put("scannedBytes", 1234)
            .put("capped", true)

        val page = ConsoleDeepSearch.fromNode(payload, now = 99)

        assertEquals(listOf("latest.log", "ab.log.gz"), page.lines.map { it.file })
        assertEquals(listOf("WARN", ConsoleLineData.UNKNOWN_LEVEL), page.lines.map { it.line.l })
        assertEquals(listOf(5L, 99L), page.lines.map { it.line.t })
        assertTrue(page.lines.all { it.line.src == ConsoleLineData.SRC_NODE })
        assertEquals("eyJ2IjoxfQ", page.cursor)
        assertFalse(page.done)
        assertEquals(1, page.scannedFiles)
        assertEquals(4, page.totalFiles)
        assertEquals(1234L, page.scannedBytes)
        assertTrue(page.capped)

        val body = page.toMap("hit")

        @Suppress("UNCHECKED_CAST")
        val first = (body["lines"] as List<JsonObject>).first()

        assertEquals("latest.log", first.getString("f"))
        assertEquals("node", first.getString("src"))
    }

    @Test
    fun `a page claiming more without a usable cursor is finished`() {
        val page = ConsoleDeepSearch.fromNode(JsonObject().put("ok", true).put("done", false).put("cursor", "not a cursor!"))

        assertTrue(page.done)
        assertNull(page.cursor)

        val finished = ConsoleDeepSearch.fromNode(JsonObject().put("ok", true).put("done", true).put("cursor", "eyJ2IjoxfQ"))

        assertTrue(finished.done)
        assertNull(finished.cursor)
    }

    @Test
    fun `at most a thousand lines are taken from one reply`() {
        val lines = JsonArray()

        repeat(1500) { lines.add(JsonObject().put("t", 1).put("l", "INFO").put("m", "hit").put("f", "latest.log")) }

        assertEquals(1000, ConsoleDeepSearch.fromNode(JsonObject().put("ok", true).put("lines", lines)).lines.size)
    }

    @Test
    fun `source errors keep their codes and a disabled console is unavailable`() {
        assertThrows(BadCursor::class.java) { throw ConsoleDeepSearch.fromNodeError("BAD_CURSOR") }
        assertThrows(BadQuery::class.java) { throw ConsoleDeepSearch.fromNodeError("BAD_QUERY") }
        assertThrows(ReadFailed::class.java) { throw ConsoleDeepSearch.fromNodeError("READ_FAILED") }
        assertThrows(FeatureUnavailable::class.java) { throw ConsoleDeepSearch.fromNodeError("UNKNOWN_SERVER") }

        val disabled = Gson().fromJson(
            """{"ok":false,"error":"DISABLED","disabled":true,"done":true,"cursor":null}""",
            ConsoleSearchResultEventRequest::class.java
        )

        val error = assertThrows(FeatureUnavailable::class.java) { ConsoleDeepSearch.fromPlugin(disabled) }

        assertEquals("FEATURE_UNAVAILABLE", error.getErrorCode())
        assertEquals("BAD_CURSOR", BadCursor().getErrorCode())
    }

    @Test
    fun `a plugin reply decodes with f and colour and is labelled as the plugin`() {
        val reply = Gson().fromJson(
            """{"ok":true,"lines":[{"t":1,"l":"INFO","m":"ab","c":[[0,2,"#7ee787",4]],"f":"2026-09-22-3.log.gz"}],""" +
                """"cursor":null,"done":true,"scannedFiles":3,"totalFiles":3,"scannedBytes":10,"capped":false}""",
            ConsoleSearchResultEventRequest::class.java
        )

        val page = ConsoleDeepSearch.fromPlugin(reply)

        assertEquals("2026-09-22-3.log.gz", page.lines.single().file)
        assertEquals(ConsoleLineData.SRC_PLUGIN, page.lines.single().line.src)
        assertEquals(1, page.lines.single().line.c?.size)
        assertTrue(page.done)
        assertEquals(3, page.scannedFiles)
    }

    @Test
    fun `the plugin message goes out as CONSOLE_SEARCH with its fields`() {
        val message = ConsoleSearchMessage("hit", null, 200, 1500)

        val encoded = JsonObject(message.encode())

        assertEquals("CONSOLE_SEARCH", encoded.getString("event"))
        assertEquals("hit", encoded.getString("query"))
        assertEquals(200, encoded.getInteger("limit"))
        assertEquals(1500, encoded.getInteger("budgetMs"))
    }

    private fun ConsoleDeepSearch.fromNodeError(code: String): Throwable = try {
        fromNode(JsonObject().put("ok", false).put("error", code).put("done", true))

        AssertionError("no error for $code")
    } catch (throwable: Throwable) {
        throwable
    }
}
