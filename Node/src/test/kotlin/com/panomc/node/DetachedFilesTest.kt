package com.panomc.node

import com.panomc.node.server.DetachedFiles
import com.panomc.node.server.ExitFile
import com.panomc.node.server.Fifo
import com.panomc.node.server.LauncherScript
import com.panomc.node.server.ProcessRecord
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The launcher script, the files around it and the record that points at them (SM-62). */
class DetachedFilesTest {
    @TempDir
    lateinit var directory: File

    private val script = LauncherScript.CONTENT

    @Test
    fun `the launcher is plain sh`() {
        assertTrue(script.startsWith("#!/bin/sh\n"))

        // No bashisms and no tools beyond the shell itself and mv.
        listOf("[[", "function ", "local ", "source ", "dirname", "readlink", "rm ", "mkfifo", "$(").forEach {
            assertFalse(script.contains(it), "launcher must not use '$it'")
        }
    }

    @Test
    fun `the launcher holds the fifo read-write and hands it to the server as stdin`() {
        assertTrue(script.contains("exec 3<>\"\$fifo\" || exit ${LauncherScript.FIFO_FAILED_EXIT}"))
        assertTrue(script.contains("fifo=\"\$dir/stdin\""))

        // The server's command line is run as given, never re-parsed; fd 3 is not leaked to it.
        assertTrue(script.contains("\"\$@\" <&3 3<&- >>\"\$out\" 2>&1 &"))
        assertTrue(script.contains("out=\"\$dir/console.out\""))
    }

    @Test
    fun `the launcher forwards signals and waits through them`() {
        listOf("TERM", "INT", "HUP").forEach { signal ->
            assertTrue(script.contains("kill -$signal \"\$child\" 2>/dev/null' $signal"), "forwards $signal")
        }

        assertTrue(script.contains("wait \"\$child\""))
        assertTrue(script.contains("kill -0 \"\$child\" 2>/dev/null && continue"))
    }

    @Test
    fun `the launcher writes the exit code atomically and exits with it`() {
        assertTrue(script.contains("exitfile=\"\$dir/exit\""))
        assertTrue(script.contains("printf '%s\\n' \"\$code\" >\"\$exitfile.tmp\" && mv -f \"\$exitfile.tmp\" \"\$exitfile\""))
        assertTrue(script.trimEnd().endsWith("exit \"\$code\""))
    }

    @Test
    fun `the files live in the node's own folder`() {
        val files = DetachedFiles(directory)

        assertEquals(File(directory, ".pano-node"), files.directory)
        assertEquals(File(files.directory, "launch.sh"), files.launcher)
        assertEquals(File(files.directory, "stdin"), files.stdin)
        assertEquals(File(files.directory, "console.out"), files.output)
        assertEquals(File(files.directory, "console.out.1"), files.rotatedOutput)
        assertEquals(File(files.directory, "exit"), files.exit)
    }

    @Test
    fun `reads the exit code and nothing that is not one`() {
        val file = File(directory, "exit")

        assertNull(ExitFile.read(file))

        file.writeText("143\n")
        assertEquals(143, ExitFile.read(file))

        file.writeText("")
        assertNull(ExitFile.read(file))

        file.writeText("garbage")
        assertNull(ExitFile.read(file))
    }

    @Test
    fun `clearing removes the exit file and its temporary sibling`() {
        val file = File(directory, "exit").apply { writeText("0\n") }
        val temporary = File(directory, "exit.tmp").apply { writeText("1\n") }

        ExitFile.clear(file)

        assertFalse(file.exists())
        assertFalse(temporary.exists())
    }

    @Test
    fun `creates a fifo where mkfifo exists`() {
        assumeTrue(File("/usr/bin/mkfifo").canExecute() || File("/bin/mkfifo").canExecute())

        val fifo = File(directory, ".pano-node/stdin")

        // Replaces whatever was there, including a stale regular file.
        fifo.parentFile.mkdirs()
        fifo.writeText("stale")

        assertTrue(Fifo.create(fifo))
        assertTrue(Fifo.isFifo(fifo))
        assertFalse(Fifo.isFifo(File(directory, "missing")))
        assertFalse(Fifo.isFifo(File(directory, ".pano-node")))
    }

    @Test
    fun `the record carries the detached fields, and an old record reads as legacy`() {
        val record = ProcessRecord(
            pid = 10,
            startedAt = 1_000,
            command = "server.jar",
            runtime = ProcessRuntime.ID,
            io = ProcessRecord.IO_DETACHED,
            launcherPid = 9,
            javaPid = 10,
            outOffset = 4096,
            logsSince = 7
        )

        assertTrue(ProcessRecordStore.write(directory, record))

        val read = ProcessRecordStore.read(directory)!!

        assertEquals(record, read)
        assertTrue(read.isDetached)

        ProcessRecordStore.file(directory).writeText(
            """{"pid":10,"startedAt":1000,"command":"server.jar","runtime":"PROCESS"}"""
        )

        val legacy = ProcessRecordStore.read(directory)!!

        assertFalse(legacy.isDetached)
        assertNull(legacy.io)
        assertNull(legacy.outOffset)
    }
}
