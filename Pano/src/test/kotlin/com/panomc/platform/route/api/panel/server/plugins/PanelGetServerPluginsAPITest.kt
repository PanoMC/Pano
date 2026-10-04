package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.feature.ServerFeatureInputs
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * `GET /api/panel/servers/:id/plugins` on a software without plugins (SM-70 follow-up, §2.4.35).
 *
 * A Vanilla server used to answer `404 NOT_EXISTS` — the node was asked for a `plugins/`
 * directory that does not exist — and the panel showed an error state plus two toasts instead of
 * its notice. It is now the resolver's refusal, the same one every other plugin endpoint gives.
 */
class PanelGetServerPluginsAPITest {
    @Test
    fun `a managed vanilla server is refused as not supported, not as missing`() {
        val error = assertThrows<FeatureUnavailable> {
            PanelGetServerPluginsAPI.refuseUnsupported(
                ServerFeatureInputs.of(
                    kind = ServerKind.MANAGED,
                    nodeConnected = true,
                    processState = ServerProcessState.STOPPED,
                    type = ServerType.VANILLA
                )
            )
        }
        val body = JsonObject(error.encode(emptyMap()))

        assertEquals(409, error.getStatusCode())
        assertEquals("FEATURE_UNAVAILABLE", body.getString("error"))
        assertEquals("plugins.list", body.getString("feature"))
        assertEquals("NOT_SUPPORTED", body.getString("reason"))
        assertFalse(body.containsKey("capability"))
    }

    @Test
    fun `vanilla stays not supported whatever state the node or the plugin is in`() {
        val cases = listOf(
            ServerFeatureInputs.of(kind = ServerKind.MANAGED, nodeConnected = false, type = ServerType.VANILLA),
            ServerFeatureInputs.of(
                kind = ServerKind.MANAGED,
                nodeConnected = true,
                processState = ServerProcessState.RUNNING,
                type = ServerType.VANILLA
            ),
            ServerFeatureInputs.of(
                kind = ServerKind.LINKED,
                pluginConnected = true,
                capabilities = setOf(ServerCapability.PLUGINS, ServerCapability.PLUGIN_INSTALL),
                type = ServerType.VANILLA
            ),
            ServerFeatureInputs.of(kind = ServerKind.LINKED, type = ServerType.VANILLA)
        )

        cases.forEach { inputs ->
            val error = assertThrows<FeatureUnavailable>("$inputs") {
                PanelGetServerPluginsAPI.refuseUnsupported(inputs)
            }

            assertEquals("NOT_SUPPORTED", JsonObject(error.encode(emptyMap())).getString("reason"), "$inputs")
        }
    }

    @Test
    fun `a software with plugins is never refused, even when nothing can list them right now`() {
        val cases = listOf(
            // Node offline: a state, explained by the page from `features.reasons`.
            ServerFeatureInputs.of(kind = ServerKind.MANAGED, nodeConnected = false, type = ServerType.PAPER),
            ServerFeatureInputs.of(kind = ServerKind.MANAGED, nodeConnected = true, type = ServerType.FABRIC),
            // A plugin without `plugins`, and one that is not connected at all.
            ServerFeatureInputs.of(
                kind = ServerKind.LINKED,
                pluginConnected = true,
                capabilities = setOf(ServerCapability.CONSOLE),
                type = ServerType.PAPER
            ),
            ServerFeatureInputs.of(kind = ServerKind.LINKED, type = ServerType.VELOCITY),
            ServerFeatureInputs.of(kind = ServerKind.MANAGED, nodeConnected = true, type = ServerType.FORGE)
        )

        cases.forEach { inputs ->
            assertDoesNotThrow("$inputs") { PanelGetServerPluginsAPI.refuseUnsupported(inputs) }
        }
    }
}
