package com.panomc.platform.node

import com.github.jknack.handlebars.Handlebars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Deleting a node or a server removes what it left on disk (SM-64, §2.4.29): the decisions the
 * delete endpoint takes, the commands Pano hands back when the node could not help, the pending
 * deletions a hello answers, and the local node's retirement.
 */
class NodeRemovalTest {
    @TempDir
    lateinit var directory: File

    // ------------------------------------------------------------------------------- decisions

    @Test
    fun `an online node that speaks protocol 4 uninstalls itself, forced or not`() {
        assertEquals(NodeRemovalDecision.UNINSTALL, NodeRemovalDecision.decide(online = true, uninstallSupported = true, force = false))
        assertEquals(NodeRemovalDecision.UNINSTALL, NodeRemovalDecision.decide(online = true, uninstallSupported = true, force = true))
    }

    @Test
    fun `an offline node is refused unless forced, and then only forgotten`() {
        assertEquals(NodeRemovalDecision.REFUSE_OFFLINE, NodeRemovalDecision.decide(online = false, uninstallSupported = true, force = false))
        assertEquals(NodeRemovalDecision.FORGET, NodeRemovalDecision.decide(online = false, uninstallSupported = true, force = true))
        assertEquals(NodeRemovalDecision.FORGET, NodeRemovalDecision.decide(online = false, uninstallSupported = false, force = true))
    }

    @Test
    fun `an online node older than protocol 4 is treated as unable to uninstall`() {
        assertEquals(NodeRemovalDecision.REFUSE_TOO_OLD, NodeRemovalDecision.decide(online = true, uninstallSupported = false, force = false))
        assertEquals(NodeRemovalDecision.FORGET, NodeRemovalDecision.decide(online = true, uninstallSupported = false, force = true))
    }

    @Test
    fun `uninstall support starts at protocol 4`() {
        // Protocol 5 (in-place servers, agent mode) still uninstalls.
        assertEquals(5, NodeProtocol.VERSION)
        assertEquals(4, NodeProtocol.NODE_UNINSTALL_VERSION)
        assertFalse(NodeProtocol.supportsUninstall(null))
        assertFalse(NodeProtocol.supportsUninstall(3))
        assertTrue(NodeProtocol.supportsUninstall(4))
        assertTrue(NodeProtocol.supportsUninstall(5))
    }

    @Test
    fun `NODE_UNINSTALL is a task kind a node cannot open by itself`() {
        assertEquals(ServerTaskKind.NODE_UNINSTALL, ServerTaskKind.fromId("NODE_UNINSTALL"))
        assertFalse(ServerTaskKind.NODE_UNINSTALL.mayBeOpenedByNode)
        assertEquals("NODE_UNINSTALL", com.panomc.platform.node.message.NodeUninstallMessage("t").getResponseName())
    }

    // ---------------------------------------------------------------------------- manual steps

    @Test
    fun `install_sh on linux gets the full systemd teardown`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "linux", "/var/lib/pano-node", NodeRuntime.PROCESS)

        assertEquals(
            listOf(
                "sudo systemctl disable --now pano-node",
                "sudo rm -rf /etc/systemd/system/pano-node.service /etc/pano-node /opt/pano-node /var/lib/pano-node",
                "sudo systemctl daemon-reload",
                "sudo userdel pano-node"
            ),
            steps
        )

        // An SSH bootstrap runs the same script.
        assertEquals(steps, NodeRemovalSteps.build(NodeBootstrap.SSH, NodeKind.REMOTE, "linux", "/var/lib/pano-node/", NodeRuntime.PROCESS))
    }

    @Test
    fun `a user install removes its whole folder`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "linux", "/home/a/.pano-node/data", NodeRuntime.PROCESS)

        assertEquals("rm -rf /home/a/.pano-node", steps.last())
        assertTrue(steps.first().startsWith("#"))
    }

    @Test
    fun `a data directory with a space is quoted`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "linux", "/srv/my node", NodeRuntime.PROCESS)

        assertEquals("rm -rf '/srv/my node'", steps.last())
    }

    @Test
    fun `install_ps1 on windows deletes the service and both folders`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "windows", "C:\\ProgramData\\PanoNode", NodeRuntime.PROCESS)

        assertEquals(
            listOf(
                "sc.exe stop PanoNode",
                "sc.exe delete PanoNode",
                "Remove-Item -Recurse -Force 'C:\\ProgramData\\PanoNode'",
                "Remove-Item -Recurse -Force \"\$env:ProgramFiles\\PanoNode\""
            ),
            steps
        )
    }

    @Test
    fun `macOS unloads the launchd agent`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "macos", "/Users/a/pano-node", NodeRuntime.PROCESS)

        assertTrue(steps.contains("launchctl unload ~/Library/LaunchAgents/com.panomc.node.plist"))
        assertEquals("rm -rf /Users/a/pano-node", steps.last())
    }

    @Test
    fun `coolify is deleted in coolify`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.COOLIFY, NodeKind.REMOTE, "linux", "/data", NodeRuntime.PROCESS)

        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.startsWith("#") })
        assertTrue(steps.any { it.contains("Coolify") })
    }

    @Test
    fun `docker servers are named in the steps`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, "linux", "/var/lib/pano-node", NodeRuntime.DOCKER)

        assertTrue(steps.any { it.startsWith("docker ps -a --filter name=pano-") })
    }

    @Test
    fun `the local node needs no steps from Pano's guesswork`() {
        assertTrue(NodeRemovalSteps.build(NodeBootstrap.LOCAL, NodeKind.LOCAL, "linux", "/srv/pano/node-data", NodeRuntime.PROCESS).isEmpty())
    }

    @Test
    fun `an unknown data path still says what to do`() {
        val steps = NodeRemovalSteps.build(NodeBootstrap.MANUAL, NodeKind.REMOTE, null, null, NodeRuntime.PROCESS)

        assertEquals(1, steps.size)
        assertTrue(steps.single().startsWith("#"))
    }

    // ------------------------------------------------------------------------ pending deletions

    @Test
    fun `a hello deletes pending servers the node still has and settles the rest`() {
        val plan = NodePendingDeletions.plan(
            pending = listOf("still-there", "already-gone", "known-again", "still-there"),
            reported = setOf("still-there", "known-again", "live"),
            known = setOf("known-again", "live")
        )

        assertEquals(listOf("still-there"), plan.delete)
        assertEquals(listOf("already-gone", "known-again"), plan.settled)
    }

    @Test
    fun `a server that only exists on the node and was never deleted by Pano is left alone`() {
        val plan = NodePendingDeletions.plan(pending = emptyList(), reported = setOf("stray"), known = emptySet())

        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.settled.isEmpty())
    }

    // ------------------------------------------------------------------------------- local node

    @Test
    fun `exit 78 is never answered with a restart, 75 and crashes are`() {
        assertTrue(LocalNodeManager.shouldStayDown(78))
        assertFalse(LocalNodeManager.shouldStayDown(LocalNodeManager.SELF_UPDATE_EXIT_CODE))
        assertFalse(LocalNodeManager.shouldStayDown(1))
        assertFalse(LocalNodeManager.shouldStayDown(0))
    }

    @Test
    fun `the local data directory is removed whole`() {
        val dataDir = File(directory, "node-data")

        File(dataDir, "servers/abc/world").mkdirs()
        File(dataDir, "servers/abc/world/level.dat").writeText("x")
        File(dataDir, LocalNodeManager.RETIRED_MARKER).writeText("{}")

        assertTrue(LocalNodeDataCleanup.delete(dataDir).isEmpty())
        assertFalse(dataDir.exists())

        // Nothing there is nothing to do.
        assertTrue(LocalNodeDataCleanup.delete(dataDir).isEmpty())
    }

    @Test
    fun `a jar configured inside the data directory survives it`() {
        val dataDir = File(directory, "node-data")
        val jar = File(dataDir, "bin/pano-node.jar")

        jar.parentFile.mkdirs()
        jar.writeText("jar")
        File(dataDir, "bin/other.txt").writeText("x")
        File(dataDir, "config.conf").writeText("x")

        val outside = File(directory, "pano-node.jar").apply { writeText("jar") }

        assertTrue(LocalNodeDataCleanup.delete(dataDir, listOf(jar, outside)).isEmpty())

        assertTrue(jar.isFile)
        assertTrue(outside.isFile)
        assertEquals(listOf("bin"), dataDir.list()!!.toList())
        assertEquals(listOf("pano-node.jar"), File(dataDir, "bin").list()!!.toList())
    }

    @Test
    fun `a process record is only trusted for the process that started when it says`() {
        val self = ProcessHandle.current()
        val started = self.info().startInstant().orElse(null)?.toEpochMilli()

        if (started != null) {
            assertTrue(LocalNodeDataCleanup.isSameProcess(self.pid(), started))
            // A pid the OS handed to somebody else a day later is not the server.
            assertFalse(LocalNodeDataCleanup.isSameProcess(self.pid(), started - 86_400_000L))
        }

        assertFalse(LocalNodeDataCleanup.isSameProcess(Long.MAX_VALUE - 1, null))
    }

    @Test
    fun `leftover servers are read from their ownership records`() {
        val dataDir = File(directory, "node-data")
        val self = ProcessHandle.current()
        val started = self.info().startInstant().orElse(null)?.toEpochMilli()

        fun record(uuid: String, json: String) {
            File(dataDir, "servers/$uuid/.pano-node").apply { mkdirs() }.resolve("process.json").writeText(json)
        }

        // This test JVM plays the part of a server that is still running.
        record("alive", """{"pid":${self.pid()},"startedAt":${started ?: "null"},"command":"server.jar","runtime":"PROCESS"}""")
        record("stale", """{"pid":${self.pid()},"startedAt":1000,"command":"server.jar","runtime":"PROCESS"}""")
        record("docker", """{"pid":0,"startedAt":0,"command":"server.jar","runtime":"DOCKER","container":"pano-docker"}""")
        File(dataDir, "servers/no-record").mkdirs()

        val leftovers = LocalNodeDataCleanup.leftoverServers(dataDir).associateBy { it.serverUuid }

        assertEquals(setOf("alive", "docker"), leftovers.keys)
        assertEquals(self.pid(), leftovers["alive"]!!.pid)
        assertEquals("pano-docker", leftovers["docker"]!!.container)

        // Containers are never stopped by Pano; they come back as leftovers for the manual steps.
        assertEquals(listOf("docker"), LocalNodeDataCleanup.stop(listOf(leftovers["docker"]!!)).map { it.serverUuid })
    }

    // ---------------------------------------------------------------------------- install.sh

    @Test
    fun `the install_sh unit stops systemd from restarting a retired node`() {
        val source = javaClass.classLoader.getResourceAsStream(NodeInstallScriptProvider.SHELL_TEMPLATE)!!
            .bufferedReader()
            .use { it.readText() }

        val rendered = Handlebars().compileInline(source).apply(
            mapOf("version" to "1.2.3", "downloadUrl" to "u", "checksumUrl" to "c", "jarName" to "pano-node.jar")
        )

        assertTrue(rendered.contains("RestartPreventExitStatus=78"))
        // A self update (75), a retirement (78) and a `systemctl stop` (143) are all clean exits.
        assertTrue(rendered.contains("SuccessExitStatus=75 78 143\n"))
    }
}
