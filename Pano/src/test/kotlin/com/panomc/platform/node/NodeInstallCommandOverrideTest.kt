package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeInstallCommandOverrideTest {
    @Test
    fun `an overridden address also fetches the script for that address`() {
        assertEquals(
            "curl -fsSL 'http://10.0.0.2:8088/api/v1/node/install.sh?panoUrl=http%3A%2F%2F10.0.0.2%3A8088' | " +
                "sh -s -- --pano 'http://10.0.0.2:8088' --code '1'",
            NodeInstallScriptProvider.shellInstallCommand("http://10.0.0.2:8088", "1", overridden = true)
        )

        assertTrue(
            NodeInstallScriptProvider.powerShellInstallCommand("http://10.0.0.2:8088", "1", overridden = true)
                .contains("'http://10.0.0.2:8088/api/v1/node/install.ps1?panoUrl=http%3A%2F%2F10.0.0.2%3A8088'")
        )
    }
}
