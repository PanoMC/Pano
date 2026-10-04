package com.panomc.platform.node.coolify

import com.panomc.platform.node.PanoUrlOverride
import com.panomc.platform.route.api.panel.node.PanelCoolifyBootstrapNodeAPI
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeCoolifyBootstrapServiceTest {
    @Test
    fun `a port range becomes the list Coolify actually validates`() {
        // The live bug: "25590-25595" went out verbatim and Coolify answered 422 for both port
        // fields, so no node was ever deployed with a range in the box.
        val ports = NodeCoolifyBootstrapService.expandPortRange("25590-25595")

        assertEquals(listOf(25590, 25591, 25592, 25593, 25594, 25595), ports)
        assertEquals("25590,25591,25592,25593,25594,25595", NodeCoolifyBootstrapService.portsExposes(ports))
        assertEquals(
            "25590:25590,25591:25591,25592:25592,25593:25593,25594:25594,25595:25595",
            NodeCoolifyBootstrapService.portsMappings(ports)
        )
    }

    @Test
    fun `a single port stays a single entry`() {
        val ports = NodeCoolifyBootstrapService.expandPortRange("25565")

        assertEquals(listOf(25565), ports)
        assertEquals("25565", NodeCoolifyBootstrapService.portsExposes(ports))
        assertEquals("25565:25565", NodeCoolifyBootstrapService.portsMappings(ports))
    }

    @Test
    fun `the default range is expanded whole`() {
        assertEquals(36, NodeCoolifyBootstrapService.expandPortRange("25565-25600").size)
    }

    @Test
    fun `a range wider than a node could use is capped`() {
        val ports = NodeCoolifyBootstrapService.expandPortRange("20000-40000")

        assertEquals(NodeCoolifyBootstrapService.MAX_PORTS, ports.size)
        assertEquals(20000, ports.first())
        assertEquals(20000 + NodeCoolifyBootstrapService.MAX_PORTS - 1, ports.last())
    }

    @Test
    fun `a nonsense range degrades to one port rather than to none`() {
        assertEquals(listOf(25600), NodeCoolifyBootstrapService.expandPortRange("25600-25565"))
        assertEquals(listOf(25565), NodeCoolifyBootstrapService.expandPortRange("25565-"))
        assertEquals(listOf(25565), NodeCoolifyBootstrapService.expandPortRange("25565-99999"))
        assertEquals(listOf(25565), NodeCoolifyBootstrapService.expandPortRange(" 25565 - abc "))
    }

    @Test
    fun `the container is told the port range it publishes`() {
        // The live bug: the range was published on the container and never passed to the node, so
        // Pano allocated 25565 inside a container that only published 25660-25669.
        val environment = NodeCoolifyBootstrapService.environment("https://pano.example.com", "t", "node", "25660-25669").toMap()

        assertEquals("25660-25669", environment["PANO_NODE_PORT_RANGE"])
        assertEquals("https://pano.example.com", environment["PANO_URL"])
        assertEquals("t", environment["PANO_BOOTSTRAP_TOKEN"])
        assertEquals("node", environment["PANO_NODE_NAME"])
        assertEquals(NodeCoolifyBootstrapService.DATA_PATH, environment["PANO_NODE_DATA"])
    }

    @Test
    fun `the range the node is told is the one actually published`() {
        fun rangeOf(typed: String) =
            NodeCoolifyBootstrapService.environment("u", "t", "n", typed).toMap()["PANO_NODE_PORT_RANGE"]

        assertEquals("25565-25565", rangeOf("25565"))
        assertEquals("20000-${20000 + NodeCoolifyBootstrapService.MAX_PORTS - 1}", rangeOf("20000-40000"))
        assertEquals("25600-25600", rangeOf("25600-25565"))
        assertNull(rangeOf("abc"))
    }

    @Test
    fun `a range with no usable port at all expands to nothing`() {
        assertTrue(NodeCoolifyBootstrapService.expandPortRange("").isEmpty())
        assertTrue(NodeCoolifyBootstrapService.expandPortRange("0-10").isEmpty())
        assertTrue(NodeCoolifyBootstrapService.expandPortRange("abc").isEmpty())
    }

    @Test
    fun `a validation failure is reported with the fields it named`() {
        val described = NodeCoolifyBootstrapService.describeErrors(
            JsonObject(
                mapOf(
                    "ports_exposes" to JsonArray(listOf("The ports exposes field format is invalid.")),
                    "ports_mappings" to JsonArray(listOf("The ports mappings field format is invalid."))
                )
            )
        )

        assertEquals(
            "ports_exposes: The ports exposes field format is invalid. " +
                "ports_mappings: The ports mappings field format is invalid.",
            described
        )
    }

    @Test
    fun `nothing to describe describes nothing`() {
        assertNull(NodeCoolifyBootstrapService.describeErrors(null))
        assertNull(NodeCoolifyBootstrapService.describeErrors(JsonObject()))
        assertNull(NodeCoolifyBootstrapService.describeErrors(JsonObject(mapOf("field" to JsonArray()))))
    }

    @Test
    fun `a dashboard url becomes an api url`() {
        assertEquals(
            "https://coolify.example.com/api/v1/applications/dockerimage",
            NodeCoolifyBootstrapService.apiUrl("https://coolify.example.com", "/applications/dockerimage")
        )
    }

    @Test
    fun `a trailing slash or an api suffix is tolerated`() {
        listOf(
            "https://coolify.example.com/",
            "https://coolify.example.com/api/v1",
            "https://coolify.example.com/api/v1/"
        ).forEach { base ->
            assertEquals(
                "https://coolify.example.com/api/v1/deploy?uuid=x",
                NodeCoolifyBootstrapService.apiUrl(base, "/deploy?uuid=x")
            )
        }
    }

    @Test
    fun `a self hosted instance under a port keeps it`() {
        assertEquals(
            "http://10.0.0.5:8000/api/v1/applications/abc/envs",
            NodeCoolifyBootstrapService.apiUrl(" http://10.0.0.5:8000 ", "/applications/abc/envs")
        )
    }

    @Test
    fun `the image and its data path match the Dockerfile`() {
        assertEquals("ghcr.io/panomc/pano-node", NodeCoolifyBootstrapService.IMAGE_NAME)
        assertEquals("/data", NodeCoolifyBootstrapService.DATA_PATH)
    }

    @Test
    fun `an overridden image has to look like a registry reference`() {
        listOf(
            "ghcr.io/panomc/pano-node",
            "registry.example.com:5000/team/pano-node",
            "pano-node",
            "my_registry.internal/pano-node"
        ).forEach { image ->
            assertTrue(PanelCoolifyBootstrapNodeAPI.IMAGE_NAME.matches(image), image)
        }

        listOf(
            "ghcr.io/panomc/pano node",
            "GHCR.io/panomc/pano-node",
            "pano-node;rm -rf /",
            "pano-node\$(id)",
            ""
        ).forEach { image ->
            assertFalse(PanelCoolifyBootstrapNodeAPI.IMAGE_NAME.matches(image), image)
        }
    }

    @Test
    fun `an overridden tag follows docker's own rule`() {
        listOf("latest", "1.0.0-alpha.513", "v1_2", "A.B-c").forEach { tag ->
            assertTrue(PanelCoolifyBootstrapNodeAPI.IMAGE_TAG.matches(tag), tag)
        }

        listOf("", "with space", "slash/tag", "a".repeat(129)).forEach { tag ->
            assertFalse(PanelCoolifyBootstrapNodeAPI.IMAGE_TAG.matches(tag), tag)
        }
    }

    @Test
    fun `a tunnelled pano url reaches the container with its port intact`() {
        // The container's PANO_URL is the one address it can reach Pano on, so an override that
        // lost its port would deploy a node that never pairs.
        assertEquals("http://127.0.0.1:18088", PanoUrlOverride.sanitize("http://127.0.0.1:18088"))
        assertEquals("http://10.0.0.5:8088", PanoUrlOverride.sanitize(" http://10.0.0.5:8088/ "))
    }
}
