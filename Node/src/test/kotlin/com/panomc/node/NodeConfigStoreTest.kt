package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NodeConfigStoreTest {
    @TempDir
    lateinit var dataDir: File

    private val sample = NodeConfig(
        platformUrl = "https://panel.example.com:443",
        token = "a-token",
        encryptionKey = "YmFzZTY0LWtleQ==",
        publicKey = "cHVibGlj",
        privateKey = "cHJpdmF0ZQ==",
        name = "Local node",
        portRangeStart = 26000,
        portRangeEnd = 26010
    )

    @Test
    fun `survives a render and parse round trip`() {
        val parsed = NodeConfigStore.parse(NodeConfigStore.render(sample))

        assertEquals(sample, parsed)
    }

    @Test
    fun `survives a save and load round trip`() {
        NodeConfigStore.save(dataDir, sample)

        assertEquals(sample, NodeConfigStore.load(dataDir))
    }

    @Test
    fun `returns defaults when nothing has been written yet`() {
        val loaded = NodeConfigStore.load(dataDir)

        assertFalse(loaded.isPaired())
        assertEquals(NodeConfig.DEFAULT_PORT_RANGE_START, loaded.portRangeStart)
        assertEquals(NodeConfig.DEFAULT_PORT_RANGE_END, loaded.portRangeEnd)
    }

    @Test
    fun `keeps a hand written file readable`() {
        val text = """
            platform {
              url = "http://127.0.0.1:8080"
              token = "t"
              encryption-key = "k"
            }
            node {
              name = "hand edited"
            }
        """.trimIndent()

        val parsed = NodeConfigStore.parse(text)

        assertEquals("hand edited", parsed.name)
        assertTrue(parsed.isPaired())
        assertEquals(NodeConfig.DEFAULT_PORT_RANGE_START, parsed.portRangeStart)
    }

    @Test
    fun `corrects a range written the wrong way round`() {
        val config = NodeConfig(portRangeStart = 26010, portRangeEnd = 26000)

        assertEquals(26000..26010, config.portRange())
    }

    @Test
    fun `is not paired until a url, a token and a key are all present`() {
        assertFalse(NodeConfig(platformUrl = "http://x", token = "t").isPaired())
        assertTrue(sample.isPaired())
    }
}
