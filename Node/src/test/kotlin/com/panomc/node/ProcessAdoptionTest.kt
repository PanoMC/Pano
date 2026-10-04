package com.panomc.node

import com.panomc.node.server.DockerCommands
import com.panomc.node.server.ProcessAdoption
import com.panomc.node.server.ProcessRecord
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The ownership record and the decision it feeds (SM-51, §2.4.16).
 *
 * The adoption rule is tested as a pure function because the expensive mistakes are all in it: a
 * reused pid, a process that is not this server at all, a record that outlived its process. None
 * of those can be reproduced by starting a real process in a test, and all of them are one wrong
 * comparison away.
 */
class ProcessAdoptionTest {
    @TempDir
    lateinit var directory: File

    private val record = ProcessRecord(
        pid = 4242,
        startedAt = 1_700_000_000_000,
        command = "server.jar",
        runtime = ProcessRuntime.ID
    )

    @Test
    fun `writes and reads a record back`() {
        assertTrue(ProcessRecordStore.write(directory, record))

        assertEquals(record, ProcessRecordStore.read(directory))
    }

    @Test
    fun `writes the record where the contract says it is`() {
        ProcessRecordStore.write(directory, record)

        val file = File(File(directory, ".pano-node"), "process.json")

        assertTrue(file.isFile)

        val text = file.readText()

        listOf("\"pid\": 4242", "\"startedAt\": 1700000000000", "\"command\": \"server.jar\"", "\"runtime\": \"PROCESS\"")
            .forEach { assertTrue(text.contains(it), "$text should contain $it") }

        // Absent rather than null: a process server has no container, and the field only exists
        // for the runtime that does.
        assertFalse(text.contains("container"))
    }

    @Test
    fun `keeps the container name for a docker server`() {
        val container = DockerCommands.containerName("a-uuid")

        ProcessRecordStore.write(directory, record.copy(runtime = "DOCKER", container = container))

        assertEquals(container, ProcessRecordStore.read(directory)?.container)
    }

    @Test
    fun `reads nothing at all when there is nothing to read`() {
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `refuses a record that says nothing usable`() {
        val file = File(directory, ".pano-node").apply { mkdirs() }.let { File(it, "process.json") }

        file.writeText("{ not json at all")

        assertNull(ProcessRecordStore.read(directory))

        file.writeText("""{"pid":0,"startedAt":1,"command":"server.jar","runtime":"PROCESS"}""")

        assertNull(ProcessRecordStore.read(directory))

        file.writeText("""{"pid":9,"startedAt":0,"command":"server.jar","runtime":"PROCESS"}""")

        assertNull(ProcessRecordStore.read(directory))

        file.writeText("""{"pid":9,"startedAt":1,"command":"","runtime":"PROCESS"}""")

        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `deletes a record`() {
        ProcessRecordStore.write(directory, record)

        ProcessRecordStore.delete(directory)

        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `adopts a live process whose start time and command line match`() {
        assertTrue(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = record.startedAt + 1_200,
                commandLine = "/usr/bin/java -Xms2048M -Xmx2048M -jar server.jar nogui",
                workingDirectory = directory.absolutePath,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `refuses a record whose process is gone`() {
        assertFalse(
            ProcessAdoption.matches(
                record = record,
                alive = false,
                startedAt = record.startedAt,
                commandLine = "java -jar server.jar",
                workingDirectory = directory.absolutePath,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `refuses a pid the host has since handed to something else`() {
        assertFalse(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = record.startedAt + ProcessAdoption.START_TOLERANCE_MILLIS + 1,
                commandLine = "java -jar server.jar",
                workingDirectory = directory.absolutePath,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `refuses a process that is not running this server's jar`() {
        assertFalse(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = record.startedAt,
                commandLine = "/usr/bin/java -jar something-else.jar",
                workingDirectory = directory.absolutePath,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `falls back to the working directory when the host will not say when a process started`() {
        assertTrue(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = null,
                commandLine = "java -jar server.jar",
                workingDirectory = directory.absolutePath,
                serverDirectory = directory.absolutePath
            )
        )

        assertFalse(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = null,
                commandLine = "java -jar server.jar",
                workingDirectory = File(directory, "elsewhere").absolutePath,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `accepts the command line alone when it is the only fact the host offers`() {
        assertTrue(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = null,
                commandLine = "java -jar server.jar",
                workingDirectory = null,
                serverDirectory = directory.absolutePath
            )
        )

        // Alive and nothing else known: there is no fact left that says this is the same process.
        assertFalse(
            ProcessAdoption.matches(
                record = record,
                alive = true,
                startedAt = null,
                commandLine = null,
                workingDirectory = null,
                serverDirectory = directory.absolutePath
            )
        )
    }

    @Test
    fun `finds no handle for a pid that cannot exist`() {
        assertNull(ProcessAdoption.handleFor(record.copy(pid = IMPOSSIBLE_PID), directory))
    }

    @Test
    fun `maps an adopted exit by whether anybody asked for it`() {
        assertEquals(ServerProcessState.STOPPED, ServerStateMachine.onAdoptedExit(requested = true))
        assertEquals(ServerProcessState.CRASHED, ServerStateMachine.onAdoptedExit(requested = false))
    }

    @Test
    fun `reads a container's running flag the way docker prints it`() {
        assertTrue(DockerCommands.parseRunning("true\n"))
        assertTrue(DockerCommands.parseRunning("\n  true  \n"))
        assertFalse(DockerCommands.parseRunning("false\n"))
        assertFalse(DockerCommands.parseRunning(""))
        assertFalse(DockerCommands.parseRunning("Error: No such object: pano-x"))

        assertEquals(
            listOf("docker", "inspect", "-f", "{{.State.Running}}", "pano-a-uuid"),
            DockerCommands.inspectRunningArgs("a-uuid")
        )
    }

    companion object {
        /** Above every `pid_max` any of these platforms allows, so nothing can ever hold it. */
        private const val IMPOSSIBLE_PID = 1_000_000_000_000L
    }
}
