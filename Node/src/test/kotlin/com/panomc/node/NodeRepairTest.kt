package com.panomc.node

import com.panomc.node.config.NodeConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeRepairTest {
    private fun paired() = NodeConfig(platformUrl = "http://127.0.0.1:8088", token = "old", encryptionKey = "k")

    @Test
    fun `a paired local node given a bootstrap token pairs again`() {
        // Pano mints one only when it has no row for its local node: the stored token is unknown to it.
        val options = NodeCli.parse(arrayOf("--pano", "http://127.0.0.1:8088")) {
            if (it == NodeCli.ENV_BOOTSTRAP_TOKEN) "fresh" else null
        }

        assertTrue(Main.needsRepairing(paired(), options))
        assertTrue(Main.needsRepairing(paired(), NodeCli.parse(arrayOf("--bootstrap-token", "fresh")) { null }))
    }

    @Test
    fun `a paired node keeps its pairing without a bootstrap token, also when somebody passes a code`() {
        assertFalse(Main.needsRepairing(paired(), NodeCli.parse(emptyArray()) { null }))
        assertFalse(Main.needsRepairing(paired(), NodeCli.parse(arrayOf("--code", "123456")) { null }))
    }

    @Test
    fun `an unpaired node is paired by the ordinary path, not by the repairing one`() {
        assertFalse(Main.needsRepairing(NodeConfig(), NodeCli.parse(arrayOf("--bootstrap-token", "fresh")) { null }))
    }
}
