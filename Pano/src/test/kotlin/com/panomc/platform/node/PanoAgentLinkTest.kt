package com.panomc.platform.node

import com.panomc.platform.db.model.Node
import com.panomc.platform.node.dto.AgentLaunchData
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import com.panomc.platform.route.api.node.NodeJarAPI
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The Pano side of a Pano Agent (A2, and the folder model of SM-74): its per-request, single-use
 * codes, the commands the "link an existing server" dialog shows, the jar served under the agent's
 * name, the directory that lets the server JSON say "agent" without a query, and (SM-76) the
 * first-run answers a new agent's server row starts with.
 */
class PanoAgentLinkTest {
    private val vertx = Vertx.vertx()

    @AfterEach
    fun close() {
        vertx.close()
    }

    private fun codes() = NodePairingCodeManager(vertx)

    @Test
    fun `a generated code is none of the codes it must not be`() {
        // A generator that keeps suggesting taken codes must be asked again until it does not.
        val sequence = ArrayDeque(listOf(23456, 23456, 11111, 34567))
        val sticky = object : Random() {
            override fun nextInt(bound: Int) = sequence.removeFirst()
        }

        assertEquals(134567, NodePairingCodeManager.generateCode(excluded = setOf(123456, 111111), random = sticky))

        repeat(500) {
            val node = NodePairingCodeManager.generateCode()

            assertNotEquals(node, NodePairingCodeManager.generateCode(excluded = setOf(node)))
        }
    }

    @Test
    fun `an agent code is minted per user, valid for a minute, and handed back while it is`() {
        val manager = codes()
        val now = 1_000_000L

        val first = manager.agentCodeFor(userId = 7, now = now)

        assertEquals(60_000L, NodePairingCodeManager.AGENT_CODE_TTL_MS)
        assertEquals(now + 60_000L, first.expiresAt)
        assertEquals(7L, first.userId)
        assertEquals(NodePairingCodeManager.AGENT_CODE_LENGTH, first.code.length)
        assertNotEquals(manager.getPairingCode().toString(), first.code)

        // Reopening the dialog must not break a command already typed on the server.
        assertEquals(first, manager.agentCodeFor(userId = 7, now = now + 59_000L))

        val other = manager.agentCodeFor(userId = 8, now = now)

        assertNotEquals(first.code, other.code)

        // Past its minute the same user gets a new one.
        val later = manager.agentCodeFor(userId = 7, now = first.expiresAt)

        assertNotEquals(first, later)
        assertEquals(first.expiresAt + 60_000L, later.expiresAt)
    }

    @Test
    fun `an agent code pairs once, and never after it expired`() {
        val manager = codes()
        val now = System.currentTimeMillis()

        val code = manager.agentCodeFor(userId = 3, now = now)

        assertNull(manager.takeAgentCode("999999x", now))
        assertNull(manager.takeAgentCode(null, now))

        val taken = manager.takeAgentCode(" ${code.code} ", now)

        assertEquals(code, taken, "whitespace from a copy-paste does not matter")
        assertNull(manager.takeAgentCode(code.code, now), "single use")

        // A pairing that failed on something other than the code gives it back.
        manager.returnAgentCode(taken!!, now)

        assertEquals(code, manager.takeAgentCode(code.code, now))

        val stale = manager.agentCodeFor(userId = 4, now = now)

        assertNull(manager.takeAgentCode(stale.code, stale.expiresAt), "an expired code pairs nothing")
    }

    @Test
    fun `at most twenty agent codes live, the oldest go first, and none is the node code`() {
        val manager = codes()
        val now = System.currentTimeMillis()

        val minted = (1L..25L).map { manager.agentCodeFor(userId = it, now = now) }

        assertEquals(NodePairingCodeManager.MAX_AGENT_CODES, manager.liveAgentCodes(now))
        assertEquals(25, minted.map { it.code }.toSet().size, "no two agent codes alike")

        minted.take(5).forEach { assertNull(manager.takeAgentCode(it.code, now), "${it.userId} was pushed out") }
        minted.drop(5).forEach { assertNotNull(manager.takeAgentCode(it.code, now)) }

        repeat(50) {
            manager.rotate()

            val live = manager.agentCodeFor(userId = 100, now = System.currentTimeMillis())

            assertNotEquals(manager.getPairingCode().toString(), live.code)
            assertFalse(manager.matches(live.code))
        }
    }

    @Test
    fun `the agent link tells the dialog everything it shows`() {
        val link = NodeInstallScriptProvider.agentLink(
            code = "123456",
            expiresAt = 42L,
            panoUrl = "https://pano.example.com",
            jarUrl = "https://pano.example.com/api/node/pano-agent.jar"
        )

        assertEquals(
            mapOf(
                "enabled" to true,
                "code" to "123456",
                "expiresAt" to 42L,
                "panoUrl" to "https://pano.example.com",
                "jarUrl" to "https://pano.example.com/api/node/pano-agent.jar",
                "jarFileName" to "pano-agent.jar",
                "downloadCommand" to "curl -fLo pano-agent.jar 'https://pano.example.com/api/node/pano-agent.jar'",
                "downloadCommandWindows" to
                    "Invoke-WebRequest -Uri 'https://pano.example.com/api/node/pano-agent.jar' -OutFile pano-agent.jar",
                "runCommand" to "java -jar pano-agent.jar --pano 'https://pano.example.com' --code 123456",
                "startCommand" to "java -jar pano-agent.jar",
                "javaVersion" to 17
            ),
            link
        )

        // The old install-script fields are gone.
        listOf("installCommand", "installCommandWindows", "serverFolderPlaceholder", "generatedAt").forEach {
            assertFalse(link.containsKey(it), it)
        }
    }

    @Test
    fun `the agent jar comes from Pano under the agent's name, or from the release`() {
        assertEquals(
            "https://p/api/node/pano-agent.jar",
            NodeInstallScriptProvider.agentJarUrl("https://p", servedByPano = true, version = "1.0.0")
        )

        assertEquals(
            NodeInstallScriptProvider.downloadUrl("https://p", servedByPano = false, version = "1.0.0"),
            NodeInstallScriptProvider.agentJarUrl("https://p", servedByPano = false, version = "1.0.0")
        )

        assertEquals("pano-agent.jar", NodeJarAPI.servedName("/api/node/pano-agent.jar"))
        assertEquals("pano-node.jar", NodeJarAPI.servedName("/api/node/pano-node.jar"))
        assertEquals("pano-agent.jar", NodeJarAPI.servedName("/api/node/pano-agent.jar.sha256".removeSuffix(".sha256")))
    }

    @Test
    fun `the node install commands and scripts are for nodes only`() {
        assertEquals(
            "curl -fsSL http://p/api/node/install.sh | sh -s -- --pano 'http://p' --code '654321'",
            NodeInstallScriptProvider.shellInstallCommand("http://p", "654321")
        )

        assertFalse(NodeInstallScriptProvider.powerShellInstallCommand("http://p", "1").contains("-Agent"))

        val shell = javaClass.classLoader.getResource(NodeInstallScriptProvider.SHELL_TEMPLATE)!!.readText()
        val powerShell = javaClass.classLoader.getResource(NodeInstallScriptProvider.POWERSHELL_TEMPLATE)!!.readText()

        listOf("--agent)", "AGENT_SERVER", "PANO_AGENT", "pano-agent-").forEach { assertFalse(shell.contains(it), it) }
        listOf("\$Agent", "-Server", "PanoAgent-").forEach { assertFalse(powerShell.contains(it), it) }
    }

    @Test
    fun `an agent's first-run answers become its server's startup settings, within the panel's bounds`() {
        assertEquals(
            ManagedServerInstallService.DEFAULT_MEMORY_MB to emptyList<String>(),
            AgentServerLinkService.launchSettings(null),
            "an agent that reported nothing gets Pano's defaults"
        )

        assertEquals(
            4096 to listOf("-XX:+UseG1GC", "-Dmotd=Hello world"),
            AgentServerLinkService.launchSettings(
                AgentLaunchData(jar = "paper.jar", memoryMb = 4096, jvmArgs = listOf(" -XX:+UseG1GC ", "", null, "-Dmotd=Hello world"))
            )
        )

        assertEquals(ServerStartupLimits.MIN_MEMORY_MB, AgentServerLinkService.launchSettings(AgentLaunchData(memoryMb = 64)).first)
        assertEquals(ServerStartupLimits.MAX_MEMORY_MB, AgentServerLinkService.launchSettings(AgentLaunchData(memoryMb = Int.MAX_VALUE)).first)

        val flood = AgentServerLinkService.launchSettings(AgentLaunchData(jvmArgs = (1..100).map { "-D$it=" + "x".repeat(300) })).second

        assertEquals(ServerStartupLimits.MAX_JVM_ARGS, flood.size)
        assertTrue(flood.all { it.length == ServerStartupLimits.MAX_JVM_ARG_LENGTH })

        // The same rules the startup settings API stores Java arguments by.
        assertEquals(listOf("-Da"), ServerStartupLimits.jvmArgs(listOf("-Da", 5, "  ")))
    }

    @Test
    fun `a hello carries the agent's launch answers, and an older one none`() {
        val gson = com.google.gson.Gson()

        val hello = gson.fromJson(
            "{\"agent\":true,\"agentServer\":\"/srv/smp\",\"agentLaunch\":{\"jar\":\"paper.jar\",\"memoryMb\":6144,\"jvmArgs\":[\"-XX:+UseG1GC\"]}}",
            NodeHelloEventRequest::class.java
        )

        assertEquals(AgentLaunchData("paper.jar", 6144, listOf("-XX:+UseG1GC")), hello.agentLaunch)
        assertNull(gson.fromJson("{\"agent\":true}", NodeHelloEventRequest::class.java).agentLaunch)
    }

    private fun node(id: Long, agent: Boolean) = Node(
        id = id,
        uuid = "n$id",
        name = "n$id",
        kind = NodeKind.REMOTE,
        aesKey = "k",
        agent = agent
    )

    @Test
    fun `the agent directory holds agents only, and forgets them with their node`() {
        val directory = AgentNodeDirectory()

        directory.seed(listOf(node(1, agent = false), node(2, agent = true)))

        assertFalse(directory.isAgent(1))
        assertTrue(directory.isAgent(2))
        assertNull(directory.get(null))

        directory.put(node(3, agent = true))
        directory.noteLinker(3, 42)

        assertEquals(42L, directory.takeLinker(3))
        assertNull(directory.takeLinker(3), "the linker is taken once")

        directory.remove(2)

        assertFalse(directory.isAgent(2))
        assertTrue(directory.isAgent(3))
    }

    @Test
    fun `agent codes are long random tokens that cannot be mistaken for a node code`() {
        val codes = (1..500).map { NodePairingCodeManager.generateAgentCode() }

        codes.forEach { code ->
            assertEquals(NodePairingCodeManager.AGENT_CODE_LENGTH, code.length)
            assertTrue(code.all { it in "abcdefghjkmnpqrstuvwxyz23456789" }, code)
        }

        assertEquals(codes.size, codes.toSet().size, "no repeats in 500 draws")

        val manager = codes()
        val minted = manager.agentCodeFor(userId = 1)

        assertEquals(NodePairingCodeManager.AGENT_CODE_LENGTH, minted.code.length)
        assertFalse(manager.matches(minted.code), "an agent code is never the node code")
    }
}
