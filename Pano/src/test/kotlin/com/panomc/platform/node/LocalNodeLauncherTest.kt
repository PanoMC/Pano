package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LocalNodeLauncherTest {
    @Test
    fun `builds the full argument list for a first pairing`() {
        val arguments = LocalNodeLauncher.buildArguments(
            javaBin = "/usr/bin/java",
            jarPath = "/opt/pano/pano-node.jar",
            panoUrl = "http://127.0.0.1:8080",
            bootstrapToken = "token-value",
            dataDir = "/srv/pano/node-data"
        )

        assertEquals(
            listOf(
                "/usr/bin/java",
                "-jar",
                "/opt/pano/pano-node.jar",
                "--pano",
                "http://127.0.0.1:8080",
                "--bootstrap-token",
                "token-value",
                "--data",
                "/srv/pano/node-data",
                "--name",
                LocalNodeLauncher.DEFAULT_NAME
            ),
            arguments
        )
    }

    @Test
    fun `leaves the token out entirely when the daemon has already paired`() {
        val arguments = LocalNodeLauncher.buildArguments(
            javaBin = "java",
            jarPath = "pano-node.jar",
            panoUrl = "http://127.0.0.1:8080",
            bootstrapToken = null,
            dataDir = "node-data"
        )

        assertFalse(arguments.contains("--bootstrap-token"))
        assertEquals(listOf("java", "-jar", "pano-node.jar", "--pano", "http://127.0.0.1:8080", "--data", "node-data", "--name", LocalNodeLauncher.DEFAULT_NAME), arguments)
    }

    @Test
    fun `keeps a name with spaces as one argument`() {
        val arguments = LocalNodeLauncher.buildArguments(
            javaBin = "java",
            jarPath = "pano-node.jar",
            panoUrl = "http://127.0.0.1:8080",
            bootstrapToken = null,
            dataDir = "node-data",
            name = "My local node"
        )

        assertEquals("My local node", arguments.last())
        assertEquals(9, arguments.size)
    }

    @Test
    fun `never hands the node a wildcard listen address to dial`() {
        assertEquals("http://127.0.0.1:8080", LocalNodeLauncher.resolvePanoUrl("0.0.0.0", 8080))
        assertEquals("http://[::1]:8080", LocalNodeLauncher.resolvePanoUrl("::", 8080))
        assertEquals("http://127.0.0.1:8080", LocalNodeLauncher.resolvePanoUrl("", 8080))
        assertEquals("http://10.0.0.5:9090", LocalNodeLauncher.resolvePanoUrl("10.0.0.5", 9090))
    }

    @Test
    fun `strips the jvm tool options pano was started with out of the node's environment`() {
        val environment = mutableMapOf(
            "JAVA_TOOL_OPTIONS" to "-Dpano.node.jar=/opt/pano/pano-node.jar",
            "_JAVA_OPTIONS" to "-Xmx512m",
            "JDK_JAVA_OPTIONS" to "--enable-preview",
            "CLASSPATH" to "/opt/pano/Pano.jar",
            "PATH" to "/usr/bin",
            "LANG" to "tr_TR.UTF-8",
            "LC_ALL" to "tr_TR.UTF-8"
        )

        LocalNodeLauncher.sanitizeChildEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin", "LANG" to "tr_TR.UTF-8", "LC_ALL" to "tr_TR.UTF-8"), environment)
    }

    @Test
    fun `leaves an environment that never had them alone`() {
        val environment = mutableMapOf("PATH" to "/usr/bin")

        assertEquals(mapOf("PATH" to "/usr/bin"), LocalNodeLauncher.sanitizeChildEnvironment(environment))
    }

    @Test
    fun `the supervisor gives up after three exits in a row`() {
        assertEquals(3, LocalNodeManager.MAX_CONSECUTIVE_FAILURES)
    }

    @Test
    fun `the supervisor backoff climbs and then caps`() {
        assertEquals(5_000L, LocalNodeManager.restartDelayMillis(1))
        assertEquals(15_000L, LocalNodeManager.restartDelayMillis(2))
        assertEquals(60_000L, LocalNodeManager.restartDelayMillis(3))
        assertEquals(300_000L, LocalNodeManager.restartDelayMillis(4))
        assertEquals(300_000L, LocalNodeManager.restartDelayMillis(12))
    }
}

class LocalNodeLauncherRunningInstanceTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var dataDir: java.io.File

    @Test
    fun `a directory no daemon ever used has no running instance`() {
        org.junit.jupiter.api.Assertions.assertNull(LocalNodeLauncher.runningInstance(dataDir))
    }

    @Test
    fun `a lock file nobody holds is a daemon that exited, whatever the pid file says`() {
        java.io.File(dataDir, LocalNodeLauncher.LOCK_FILE_NAME).writeText("")
        java.io.File(dataDir, LocalNodeLauncher.PID_FILE_NAME).writeText("4242")

        org.junit.jupiter.api.Assertions.assertNull(LocalNodeLauncher.runningInstance(dataDir))
    }

    @Test
    fun `a held lock is a running daemon, identified by its pid file`() {
        val lockFile = java.io.File(dataDir, LocalNodeLauncher.LOCK_FILE_NAME)

        java.io.File(dataDir, LocalNodeLauncher.PID_FILE_NAME).writeText("4242\n")

        java.io.RandomAccessFile(lockFile, "rw").use { file ->
            val held = file.channel.lock()

            try {
                assertEquals(LocalNodeLauncher.RunningInstance(4242L), LocalNodeLauncher.runningInstance(dataDir))
            } finally {
                held.release()
            }
        }

        org.junit.jupiter.api.Assertions.assertNull(LocalNodeLauncher.runningInstance(dataDir))
    }

    @Test
    fun `a held lock without a readable pid still counts as running`() {
        java.io.RandomAccessFile(java.io.File(dataDir, LocalNodeLauncher.LOCK_FILE_NAME), "rw").use { file ->
            val held = file.channel.lock()

            try {
                assertEquals(LocalNodeLauncher.RunningInstance(null), LocalNodeLauncher.runningInstance(dataDir))
            } finally {
                held.release()
            }
        }
    }
}
