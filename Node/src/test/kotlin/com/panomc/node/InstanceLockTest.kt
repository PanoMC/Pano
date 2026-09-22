package com.panomc.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InstanceLockTest {
    @TempDir
    lateinit var dataDir: File

    @Test
    fun `the first daemon takes the directory and leaves its pid behind`() {
        val lock = InstanceLock.acquire(dataDir)

        assertNotNull(lock)
        assertEquals(ProcessHandle.current().pid(), InstanceLock.holderPid(dataDir))

        lock!!.release()
    }

    @Test
    fun `a second daemon on the same directory is turned away`() {
        val first = InstanceLock.acquire(dataDir)

        try {
            assertNull(InstanceLock.acquire(dataDir))
        } finally {
            first!!.release()
        }
    }

    @Test
    fun `releasing hands the directory to the next daemon and forgets the pid`() {
        InstanceLock.acquire(dataDir)!!.release()

        assertFalse(File(dataDir, InstanceLock.PID_FILE).exists())

        val next = InstanceLock.acquire(dataDir)

        assertNotNull(next)

        next!!.release()
    }

    @Test
    fun `no pid file means no holder rather than a crash`() {
        assertNull(InstanceLock.holderPid(dataDir))

        File(dataDir, InstanceLock.PID_FILE).writeText("not a pid")

        assertNull(InstanceLock.holderPid(dataDir))
    }
}
