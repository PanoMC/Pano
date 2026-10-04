package com.panomc.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NodeCliTest {
    private val noEnv: (String) -> String? = { null }

    @Test
    fun `reads a pairing invocation`() {
        val options = NodeCli.parse(arrayOf("--pano", "https://panel.example.com/", "--code", "123456"), noEnv)

        assertEquals("https://panel.example.com", options.platformUrl)
        assertEquals("123456", options.pairingCode)
        assertNull(options.bootstrapToken)
        assertEquals(File(NodeCli.DEFAULT_DATA_DIR).absoluteFile, options.dataDir)
    }

    @Test
    fun `reads a bootstrap invocation with an explicit data directory`() {
        val options = NodeCli.parse(
            arrayOf("--pano", "http://127.0.0.1:8080", "--bootstrap-token", "abc", "--data", "/tmp/nd", "--name", "Local node"),
            noEnv
        )

        assertEquals("abc", options.bootstrapToken)
        assertEquals(File("/tmp/nd").absoluteFile, options.dataDir)
        assertEquals("Local node", options.name)
    }

    @Test
    fun `falls back to the environment`() {
        val env = mapOf(
            NodeCli.ENV_URL to "http://127.0.0.1:8080",
            NodeCli.ENV_BOOTSTRAP_TOKEN to "from-env",
            NodeCli.ENV_DATA to "/data/node"
        )

        val options = NodeCli.parse(emptyArray()) { env[it] }

        assertEquals("http://127.0.0.1:8080", options.platformUrl)
        assertEquals("from-env", options.bootstrapToken)
        assertEquals(File("/data/node").absoluteFile, options.dataDir)
    }

    @Test
    fun `a flag beats the matching variable`() {
        val env = mapOf(NodeCli.ENV_URL to "http://from-env")

        val options = NodeCli.parse(arrayOf("--pano", "http://from-flag")) { env[it] }

        assertEquals("http://from-flag", options.platformUrl)
    }

    @Test
    fun `reads the service actions`() {
        assertEquals(
            NodeOptions.ServiceAction.INSTALL,
            NodeCli.parse(arrayOf("--service", "install"), noEnv).service
        )

        assertEquals(
            NodeOptions.ServiceAction.UNINSTALL,
            NodeCli.parse(arrayOf("--service", "uninstall"), noEnv).service
        )
    }

    @Test
    fun `refuses an unknown option, a missing value and an unsupported runtime`() {
        assertThrows(IllegalArgumentException::class.java) { NodeCli.parse(arrayOf("--nope"), noEnv) }
        assertThrows(IllegalArgumentException::class.java) { NodeCli.parse(arrayOf("--pano"), noEnv) }
        assertThrows(IllegalArgumentException::class.java) { NodeCli.parse(arrayOf("--runtime", "PODMAN"), noEnv) }
        assertThrows(IllegalArgumentException::class.java) { NodeCli.parse(arrayOf("--service", "maybe"), noEnv) }
    }

    @Test
    fun `defaults to the process runtime`() {
        assertEquals(NodeCli.DEFAULT_RUNTIME, NodeCli.parse(emptyArray(), noEnv).runtime)
    }

    @Test
    fun `accepts the docker runtime from a flag or the environment`() {
        assertEquals(NodeCli.DOCKER_RUNTIME, NodeCli.parse(arrayOf("--runtime", "docker"), noEnv).runtime)
        assertEquals(
            NodeCli.DOCKER_RUNTIME,
            NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_RUNTIME) "DOCKER" else null }.runtime
        )
    }

    @Test
    fun `quotes a shell did not remove are taken off the url and the code`() {
        // The Pano Agent's command as Windows cmd.exe hands it over.
        val options = NodeCli.parse(arrayOf("--pano", "'https://pano.example.com/'", "--code", "\"123456\""), noEnv)

        assertEquals("https://pano.example.com", options.platformUrl)
        assertEquals("123456", options.pairingCode)
        assertEquals("it's", NodeCli.unquote("it's"))
        assertEquals("'", NodeCli.unquote("'"))
    }

    @Test
    fun `--no-input and PANO_AGENT_NO_INPUT keep a Pano Agent from asking, and stay the launcher's`() {
        assertFalse(NodeCli.parse(emptyArray(), noEnv).noInput)
        assertTrue(NodeCli.parse(arrayOf("--no-input"), noEnv).noInput)
        assertTrue(NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_NO_INPUT) "true" else null }.noInput)
        assertFalse(NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_NO_INPUT) "0" else null }.noInput)
        assertThrows(IllegalArgumentException::class.java) {
            NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_NO_INPUT) "maybe" else null }
        }

        assertEquals(
            listOf("--pano", "https://p", "--code", "c"),
            com.panomc.node.agent.AgentLauncher.passThrough(arrayOf("--no-input", "--pano", "https://p", "--code", "c"))
        )
        assertTrue(NodeCli.USAGE.contains("--no-input"), NodeCli.USAGE)
    }

    @Test
    fun `asks for help`() {
        assertTrue(NodeCli.parse(arrayOf("--help"), noEnv).help)
    }

    @Test
    fun `reads a port range from --port-range or PANO_NODE_PORT_RANGE, the flag first`() {
        val env = mapOf(NodeCli.ENV_PORT_RANGE to " 25660 - 25669 ")

        assertNull(NodeCli.parse(emptyArray(), noEnv).portRange)
        assertEquals(25660..25669, NodeCli.parse(arrayOf("--port-range", "25660-25669"), noEnv).portRange)
        assertEquals(25660..25669, NodeCli.parse(emptyArray()) { env[it] }.portRange)
        assertEquals(30000..30010, NodeCli.parse(arrayOf("--port-range", "30000-30010")) { env[it] }.portRange)
        // One port is a range of one.
        assertEquals(25565..25565, NodeCli.parse(arrayOf("--port-range", "25565-25565"), noEnv).portRange)
        // A blank variable is an unset one, as a compose file with `PANO_NODE_PORT_RANGE=` means.
        assertNull(NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_PORT_RANGE) "  " else null }.portRange)
    }

    @Test
    fun `refuses a port range that is not start-end in 1-65535, in order`() {
        val invalid = listOf("25660", "25669-25660", "0-10", "25660-70000", "abc", "25660-", "-25669", "1-2-3", "")

        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java, { NodeCli.parse(arrayOf("--port-range", value), noEnv) }, value)

            if (value.isNotBlank()) {
                val error = assertThrows(IllegalArgumentException::class.java, {
                    NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_PORT_RANGE) value else null }
                }, value)

                assertTrue(error.message!!.contains(NodeCli.ENV_PORT_RANGE), error.message)
            }
        }

        assertThrows(IllegalArgumentException::class.java) { NodeCli.parse(arrayOf("--port-range"), noEnv) }
    }

    @Test
    fun `a valid --port-range covers a broken PANO_NODE_PORT_RANGE`() {
        val options = NodeCli.parse(arrayOf("--port-range", "25660-25669")) {
            if (it == NodeCli.ENV_PORT_RANGE) "nonsense" else null
        }

        assertEquals(25660..25669, options.portRange)
    }

    @Test
    fun `--port-range takes a value, is passed on to an agent's worker and is in the usage`() {
        assertTrue(NodeCli.PORT_RANGE_FLAG in NodeCli.VALUE_FLAGS)
        assertEquals(
            listOf("--port-range", "25660-25669"),
            com.panomc.node.agent.AgentLauncher.passThrough(arrayOf("--port-range", "25660-25669"))
        )
        assertTrue(NodeCli.USAGE.contains("--port-range"), NodeCli.USAGE)
        assertTrue(NodeCli.USAGE.contains(NodeCli.ENV_PORT_RANGE), NodeCli.USAGE)
    }
}
