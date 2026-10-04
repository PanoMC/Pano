package com.panomc.node

import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.host.UninstallSteps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The commands a removed node hands back per install type (SM-64, §2.4.29 B). Pure: every branch is
 * checked from any OS by describing the host rather than being on it.
 */
class UninstallStepsTest {
    private fun environment(
        os: String = "linux",
        isRoot: Boolean = false,
        inContainer: Boolean = false,
        systemdUnit: String? = null,
        selfServiceUnit: String? = null,
        jarPath: String? = null,
        dataDir: String = "/var/lib/pano-node",
        user: String = "pano-node"
    ) = UninstallEnvironment(os, isRoot, inContainer, systemdUnit, selfServiceUnit, jarPath, dataDir, user)

    @Test
    fun `install_sh on linux without root gets the full systemd teardown`() {
        val steps = UninstallSteps.build(
            environment(
                systemdUnit = UninstallEnvironment.SYSTEMD_UNIT_PATH,
                jarPath = "/opt/pano-node/pano-node.jar"
            )
        )

        assertEquals(
            listOf(
                "sudo systemctl disable --now pano-node",
                "sudo rm -rf /etc/systemd/system/pano-node.service /etc/pano-node /opt/pano-node /var/lib/pano-node",
                "sudo systemctl daemon-reload",
                "sudo userdel pano-node"
            ),
            steps
        )
    }

    @Test
    fun `install_sh as root that removed its own service leaves only the data directory`() {
        val steps = UninstallSteps.build(
            environment(isRoot = true, systemdUnit = UninstallEnvironment.SYSTEMD_UNIT_PATH, user = "root"),
            serviceHandled = true
        )

        assertEquals(listOf("rm -rf /var/lib/pano-node"), steps)
    }

    @Test
    fun `a user install removes its whole folder`() {
        val steps = UninstallSteps.build(
            environment(
                jarPath = "/home/ahmet/.pano-node/pano-node.jar",
                dataDir = "/home/ahmet/.pano-node/data",
                user = "ahmet"
            )
        )

        assertEquals(listOf("rm -rf /home/ahmet/.pano-node"), steps)
    }

    @Test
    fun `a jar run by hand gets the data directory removed and the jar only pointed at`() {
        val steps = UninstallSteps.build(
            environment(jarPath = "/home/a/My Downloads/pano-node.jar", dataDir = "/home/a/node data", user = "a")
        )

        assertEquals(listOf("rm -rf '/home/a/node data'", "# /home/a/My Downloads/pano-node.jar can be deleted too if no other node runs from it"), steps)
    }

    @Test
    fun `a self-written unit on linux is disabled first`() {
        val steps = UninstallSteps.build(
            environment(selfServiceUnit = "/srv/node/service/pano-node.service", dataDir = "/srv/node", user = "mc")
        )

        assertEquals("sudo systemctl disable --now pano-node", steps[0])
        assertTrue(steps.contains("rm -rf /srv/node"))
    }

    @Test
    fun `macOS with a launchd agent unloads it`() {
        val steps = UninstallSteps.build(
            environment(
                os = "macos",
                selfServiceUnit = "/Users/a/pano/service/com.panomc.node.plist",
                dataDir = "/Users/a/pano",
                jarPath = "/Users/a/pano-node.jar",
                user = "a"
            )
        )

        assertEquals(
            listOf(
                "launchctl unload ~/Library/LaunchAgents/com.panomc.node.plist",
                "rm -f ~/Library/LaunchAgents/com.panomc.node.plist",
                "rm -rf /Users/a/pano",
                "# /Users/a/pano-node.jar can be deleted too if no other node runs from it"
            ),
            steps
        )
    }

    @Test
    fun `install_ps1 on windows deletes the service and both folders`() {
        val steps = UninstallSteps.build(
            environment(
                os = "windows",
                jarPath = "C:\\Program Files\\PanoNode\\pano-node.jar",
                dataDir = "C:\\ProgramData\\PanoNode",
                user = "SYSTEM"
            )
        )

        assertEquals("sc.exe stop PanoNode", steps[0])
        assertEquals("sc.exe delete PanoNode", steps[1])
        assertTrue(steps.contains("Remove-Item -Recurse -Force 'C:\\ProgramData\\PanoNode'"), steps.toString())
    }

    @Test
    fun `a container is the platform's to delete`() {
        val steps = UninstallSteps.build(environment(inContainer = true, dataDir = "/data"))

        assertTrue(steps.all { it.startsWith("#") })
        assertTrue(steps.any { it.contains("Coolify") })
    }

    @Test
    fun `quoting cannot be escaped`() {
        assertEquals("'it'\\''s'", UninstallSteps.shellQuote("it's"))
        assertEquals("'it''s'", UninstallSteps.powerShellQuote("it's"))
    }
}
