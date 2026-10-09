package com.panomc.node

import com.panomc.node.net.NodePaths
import com.panomc.node.net.NodeProtocol
import com.panomc.node.files.TransferService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class NodePathsTest {
    @Test
    fun `every path sits under api v1 node`() {
        listOf(
            NodePaths.CONNECT, NodePaths.CONNECTION, NodePaths.TRANSFER,
            NodePaths.AGENT_JAR_CHECKSUM, NodePaths.NODE_JAR_CHECKSUM
        ).forEach { assertTrue(it.startsWith("/api/v1/node/"), it) }

        assertEquals("/api/v1/node/transfer/", TransferService.TRANSFER_PATH)
    }

    @Test
    fun `protocol is at least the cutover value`() {
        assertTrue(NodeProtocol.VERSION >= 6)
    }

    @Test
    fun `reads the error code of the envelope only`() {
        assertEquals("NEED_PERMISSION", NodePaths.errorCode("{\"error\":{\"code\":\"NEED_PERMISSION\",\"message\":\"x\"}}"))
        assertEquals("NOT_EXISTS", NodePaths.errorCode("{ \"error\" : { \"code\" : \"NOT_EXISTS\" } }"))
        assertNull(NodePaths.errorCode("{\"result\":\"error\",\"error\":\"NOT_EXISTS\"}"))
        assertNull(NodePaths.errorCode("{\"token\":\"abc\"}"))
        assertNull(NodePaths.errorCode(null))
    }
}
