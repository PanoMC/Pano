package com.panomc.node

import com.panomc.node.server.DockerCommands
import com.panomc.node.server.ServerSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerCommandsTest {
    private val spec = ServerSpec(
        uuid = "abc",
        name = "lobby",
        software = "paper",
        version = "1.21.8",
        memoryMb = 2048,
        jvmArgs = listOf("-XX:+UseG1GC"),
        port = 25565
    )

    @Test
    fun `a container is named after the server uuid`() {
        assertEquals("pano-abc", DockerCommands.containerName("abc"))
    }

    @Test
    fun `the image is the temurin jre for that java major`() {
        assertEquals("eclipse-temurin:21-jre", DockerCommands.imageFor(21))
        assertEquals("eclipse-temurin:8-jre", DockerCommands.imageFor(8))
    }

    @Test
    fun `an impossible java major falls back rather than building a tag that does not exist`() {
        assertEquals("eclipse-temurin:21-jre", DockerCommands.imageFor(0))
        assertEquals("eclipse-temurin:21-jre", DockerCommands.imageFor(-3))
        assertEquals("eclipse-temurin:21-jre", DockerCommands.imageFor(9999))
    }

    @Test
    fun `create carries memory, the mount, the work dir and the blanked tool options`() {
        val args = DockerCommands.createArgs("abc", "eclipse-temurin:21-jre", spec, "/srv/a b", "server.jar", 25565, null)

        assertEquals(listOf("docker", "create"), args.take(2))
        assertTrue(args.containsPair("--name", "pano-abc"))
        assertTrue(args.containsPair("--memory", "2048m"))
        assertTrue(args.containsPair("--workdir", "/data"))
        // One argument, so a directory with a space in it can never become two.
        assertTrue(args.containsPair("--volume", "/srv/a b:/data"))
        assertTrue(args.containsPair("-e", "JAVA_TOOL_OPTIONS="))
        assertTrue(args.contains("--interactive"))
        assertTrue(args.containsPair("--restart", "no"))
    }

    @Test
    fun `create maps the host gateway, so a Pano on this machine is reachable from inside`() {
        val args = DockerCommands.createArgs("abc", "img", spec, "/srv/a", "server.jar", 25565, null)

        assertTrue(args.containsPair("--add-host", "host.docker.internal:host-gateway"))
    }

    @Test
    fun `create publishes the game port one to one`() {
        val args = DockerCommands.createArgs("abc", "img", spec, "/srv/a", "server.jar", 25566, null)

        assertTrue(args.containsPair("--publish", "25566:25566"))
    }

    @Test
    fun `an unallocated port publishes nothing rather than a broken mapping`() {
        val args = DockerCommands.createArgs("abc", "img", spec.copy(port = 0), "/srv/a", "server.jar", 0, null)

        assertFalse(args.contains("--publish"))
    }

    @Test
    fun `create runs the image's own java, never this host's path`() {
        val args = DockerCommands.createArgs("abc", "img", spec, "/srv/a", "server.jar", 25565, null)
        val command = args.subList(args.indexOf("img") + 1, args.size)

        assertEquals(
            listOf("java", "-Xms307M", "-Xmx1229M", "-Djansi.passthrough=true", "-XX:+UseG1GC", "-jar", "server.jar", "nogui"),
            command
        )
    }

    @Test
    fun `a proxy gets no nogui argument inside a container either`() {
        val args = DockerCommands.createArgs(
            "abc",
            "img",
            spec.copy(software = "velocity"),
            "/srv/a",
            "server.jar",
            25565,
            null
        )

        assertFalse(args.contains("nogui"))
    }

    @Test
    fun `a posix user is passed through and a null one is left out`() {
        assertTrue(
            DockerCommands.createArgs("abc", "img", spec, "/srv/a", "server.jar", 25565, "1000:1000")
                .containsPair("--user", "1000:1000")
        )
        assertFalse(
            DockerCommands.createArgs("abc", "img", spec, "/srv/a", "server.jar", 25565, null).contains("--user")
        )
    }

    @Test
    fun `start attaches so the console pipeline keeps working`() {
        assertEquals(
            listOf("docker", "start", "--attach", "--interactive", "pano-abc"),
            DockerCommands.startArgs("abc")
        )
    }

    @Test
    fun `stop, kill and remove address the container by name`() {
        assertEquals(listOf("docker", "stop", "--time", "30", "pano-abc"), DockerCommands.stopArgs("abc", 30))
        assertEquals(listOf("docker", "kill", "pano-abc"), DockerCommands.killArgs("abc"))
        assertEquals(listOf("docker", "rm", "--force", "pano-abc"), DockerCommands.removeArgs("abc"))
    }

    @Test
    fun `stats asks for one json line`() {
        assertEquals(
            listOf("docker", "stats", "--no-stream", "--format", "{{json .}}", "pano-abc"),
            DockerCommands.statsArgs("abc")
        )
    }

    @Test
    fun `version and pull are plain argument lists`() {
        assertEquals("docker", DockerCommands.versionArgs().first())
        assertEquals("version", DockerCommands.versionArgs()[1])
        assertEquals(listOf("docker", "pull", "img"), DockerCommands.pullArgs("img"))
        assertEquals(listOf("docker", "image", "inspect", "img"), DockerCommands.imageInspectArgs("img"))
    }

    @Test
    fun `stats are read out of docker's human formatting`() {
        val sample = DockerCommands.parseStats(
            """{"BlockIO":"0B / 0B","CPUPerc":"12.34%","MemUsage":"1.5GiB / 4GiB","Name":"pano-abc"}"""
        )

        assertEquals(12.34, sample.cpuPercent)
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), sample.residentBytes)
    }

    @Test
    fun `an unreadable stats line reports nothing rather than a made-up number`() {
        val sample = DockerCommands.parseStats("not json at all")

        assertNull(sample.cpuPercent)
        assertNull(sample.residentBytes)
        assertNull(sample.netRxTotal)
        assertNull(sample.netTxTotal)
    }

    @Test
    fun `the container's network totals come out of NetIO, received first`() {
        val sample = DockerCommands.parseStats(
            """{"CPUPerc":"1.00%","MemUsage":"1GiB / 4GiB","NetIO":"1.2MB / 3.4kB","Name":"pano-abc"}"""
        )

        assertEquals(1_200_000L, sample.netRxTotal)
        assertEquals(3_400L, sample.netTxTotal)
    }

    @Test
    fun `net io is read in decimal and binary units alike, and a half-missing pair is no pair`() {
        assertEquals(1_500_000_000L to 0L, DockerCommands.parsePair("1.5GB / 0B"))
        assertEquals((2L * 1024 * 1024 * 1024) to (512L * 1024), DockerCommands.parsePair("2GiB / 512KiB"))
        assertEquals(1_024L to 999L, DockerCommands.parsePair("1.024kB / 999B"))

        assertNull(DockerCommands.parsePair("--"))
        assertNull(DockerCommands.parsePair("1.2MB / --"))
        assertNull(DockerCommands.parsePair("1.2MB"))
        assertNull(DockerCommands.parseStats("""{"CPUPerc":"1.00%","MemUsage":"1GiB / 4GiB"}""").netRxTotal)
    }

    @Test
    fun `cpu over one core is kept, because docker counts all of them`() {
        assertEquals(350.0, DockerCommands.parsePercent("350.00%"))
        assertNull(DockerCommands.parsePercent("--"))
    }

    @Test
    fun `both binary and decimal units are understood`() {
        assertEquals(512L * 1024 * 1024, DockerCommands.parseBytes("512MiB"))
        assertEquals(1_000_000L, DockerCommands.parseBytes("1MB"))
        assertEquals(1024L, DockerCommands.parseBytes("1KiB"))
        assertEquals(7L, DockerCommands.parseBytes("7B"))
        assertNull(DockerCommands.parseBytes("lots"))
        assertNull(DockerCommands.parseBytes(""))
    }

    @Test
    fun `re-attaching uses a plain attach that does not proxy signals`() {
        assertEquals(
            listOf("docker", "attach", "--no-stdin=false", "--sig-proxy=false", "pano-abc"),
            DockerCommands.attachArgs("abc")
        )
    }

    @Test
    fun `logs are timestamped and start at the recorded instant`() {
        assertEquals(
            listOf("docker", "logs", "--timestamps", "--since", "1700000000.000000042", "--follow", "pano-abc"),
            DockerCommands.logsArgs("abc", 1_700_000_000_000_000_042L, follow = true)
        )
        assertFalse(DockerCommands.logsArgs("abc", 0, follow = false).contains("--follow"))
        assertEquals("0.000000000", DockerCommands.formatSince(-5))
    }

    @Test
    fun `splits the timestamp off a log line`() {
        val (at, text) = DockerCommands.splitTimestamp("2023-11-14T22:13:20.000000042Z [12:00:00 INFO]: Done")

        assertEquals(1_700_000_000_000_000_042L, at)
        assertEquals("[12:00:00 INFO]: Done", text)

        assertEquals(null to "no timestamp here", DockerCommands.splitTimestamp("no timestamp here"))
        assertEquals(null to "", DockerCommands.splitTimestamp(""))
    }

    @Test
    fun `reads the exit code docker kept`() {
        assertEquals(listOf("docker", "inspect", "-f", "{{.State.ExitCode}}", "pano-abc"), DockerCommands.inspectExitCodeArgs("abc"))
        assertEquals(137, DockerCommands.parseExitCode("137\n"))
        assertNull(DockerCommands.parseExitCode("Error: No such object"))
    }

    private fun List<String>.containsPair(flag: String, value: String): Boolean {
        val at = indexOf(flag)

        return at >= 0 && at + 1 < size && this[at + 1] == value
    }
}
