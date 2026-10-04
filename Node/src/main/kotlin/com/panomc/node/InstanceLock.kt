package com.panomc.node

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * One daemon per data directory.
 *
 * Two daemons on the same directory would both supervise the same server processes and both
 * answer Pano as the same node, and nothing downstream is built to survive that. It is also the
 * normal case rather than an accident: Pano leaves its local node running across its own restarts
 * (a panel restart is no reason to disconnect players), so the daemon a fresh Pano starts is
 * usually the second one, and it has to lose quietly.
 *
 * The lock is an OS file lock held for the life of the process, so a daemon that dies any way at
 * all releases it; the pid file next to it is informational, for the log line and for Pano, which
 * reads it to adopt the running daemon instead of starting another.
 */
class InstanceLock private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
    private val pidFile: File
) {
    fun release() {
        try {
            lock.release()
        } catch (_: Exception) {
        }

        try {
            file.close()
        } catch (_: Exception) {
        }

        pidFile.delete()
    }

    companion object {
        const val LOCK_FILE = "pano-node.lock"
        const val PID_FILE = "pano-node.pid"

        /** The lock, or null when another daemon holds it; [holderPid] then says which, if it can. */
        fun acquire(dataDir: File): InstanceLock? {
            val file = RandomAccessFile(File(dataDir, LOCK_FILE), "rw")

            // Another process answers with null; another thread of this one answers by throwing.
            // Both mean the same thing here.
            val lock = try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                file.close()

                return null
            }

            val pidFile = File(dataDir, PID_FILE)

            pidFile.writeText(ProcessHandle.current().pid().toString())

            return InstanceLock(file, lock, pidFile)
        }

        /** What the pid file says, which is only as fresh as the last daemon that wrote it. */
        fun holderPid(dataDir: File): Long? =
            File(dataDir, PID_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }
}
