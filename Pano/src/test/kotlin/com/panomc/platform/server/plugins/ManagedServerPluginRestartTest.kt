package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.ManagedServerPluginService.Companion.restartRequiredFor
import com.panomc.platform.server.plugins.dto.ServerPluginFileData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedServerPluginRestartTest {
    private fun file(name: String, plugin: String?, enabled: Boolean = true) =
        ServerPluginFileData(filename = name, size = 1, modified = 0, matchedPlugin = plugin, enabled = enabled)

    @Test
    fun `nothing to restart when the directory is what is running`() {
        assertFalse(
            restartRequiredFor(
                listOf(file("Vault.jar", "Vault"), file("Old.jar.disabled", null, enabled = false)),
                online = true
            )
        )
    }

    @Test
    fun `a jar switched on since the start needs a restart`() {
        assertTrue(restartRequiredFor(listOf(file("Vault.jar", null)), online = true))
    }

    @Test
    fun `a loaded plugin whose jar was switched off needs a restart`() {
        assertTrue(restartRequiredFor(listOf(file("Vault.jar.disabled", "Vault", enabled = false)), online = true))
    }

    @Test
    fun `an old copy switched off beside the one that loaded needs none`() {
        assertFalse(
            restartRequiredFor(
                listOf(
                    file("EssentialsX-2.22.0.jar", "Essentials"),
                    file("EssentialsX-2.21.2.jar.disabled", "Essentials", enabled = false)
                ),
                online = true
            )
        )
    }

    @Test
    fun `an offline server never needs one`() {
        assertFalse(restartRequiredFor(listOf(file("Vault.jar", null)), online = false))
    }
}
