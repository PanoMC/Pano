package com.panomc.platform.node

import com.google.gson.Gson
import com.panomc.platform.node.dto.NodePortRangeData
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The hello's optional `portRange`, as it comes off the wire.
 *
 * Decoded with a plain Gson, as `NodeManager` decodes it. A node that says nothing, or says
 * something that is not a range, is a node with no known range: Pano then allocates as it always
 * has, and the node itself moves a port that does not fit.
 */
class NodePortRangeHelloTest {
    private val gson = Gson()

    private fun hello(json: String) = gson.fromJson(json, NodeHelloEventRequest::class.java)

    @Test
    fun `a new node's hello carries its range`() {
        val request = hello("""{"event":"NODE_HELLO","protocolVersion":5,"portRange":{"start":25660,"end":25669}}""")

        assertEquals(NodePortRangeData(25660, 25669), request.portRange)
        assertEquals(25660..25669, request.portRange?.toRangeOrNull())
    }

    @Test
    fun `an older node's hello has no range`() {
        assertNull(hello("""{"event":"NODE_HELLO","protocolVersion":5}""").portRange)
        assertNull(hello("""{"portRange":null}""").portRange?.toRangeOrNull())
    }

    @Test
    fun `a range that is not one is no range at all`() {
        listOf(
            """{"start":25669,"end":25660}""",
            """{"start":0,"end":10}""",
            """{"start":25660,"end":70000}""",
            """{"start":25660}""",
            """{}"""
        ).forEach { range ->
            assertNull(hello("""{"portRange":$range}""").portRange?.toRangeOrNull(), range)
        }

        assertEquals(25565..25565, NodePortRangeData(25565, 25565).toRangeOrNull())
    }

    @Test
    fun `the announced range is what the allocation walks`() {
        val range = hello("""{"portRange":{"start":25660,"end":25669}}""").portRange?.toRangeOrNull()

        assertEquals(listOf(25660), ServerPortAllocator.allocate(1, setOf(25565), range))
        assertEquals(listOf(25566), ServerPortAllocator.allocate(1, setOf(25565), hello("{}").portRange?.toRangeOrNull()))
    }
}
