package com.panomc.node.host

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * CPU and resident memory of one managed server process.
 *
 * CPU comes from the JDK's own process handle: the total CPU time a process has burned is
 * cumulative, so a usable percentage is the difference between two readings divided by the wall
 * time between them and the core count. That means the first sample after a start has nothing to
 * compare against and reports null, which is correct -- the panel shows a dash for one tick
 * instead of a made-up number.
 *
 * Resident memory has no portable API at all. Linux has it in `/proc`, and everywhere else the
 * node shells out to `ps` with an argument list (never a shell string). When neither works the
 * value is null and Pano simply has one less number to draw.
 */
class ProcessMetrics : com.panomc.node.server.ServerRuntime.ProcessSampler {
    private data class Sample(val cpuNanos: Long, val atNanos: Long)

    private var previous: Sample? = null

    /** Fraction of one host's worth of CPU, as a 0..100 percentage, or null on the first sample. */
    override fun cpuPercent(handle: ProcessHandle): Double? {
        val cpuDuration: Duration = handle.info().totalCpuDuration().orElse(null) ?: return null

        val now = System.nanoTime()
        val current = Sample(cpuDuration.toNanos(), now)
        val last = previous

        previous = current

        if (last == null) {
            return null
        }

        val elapsed = current.atNanos - last.atNanos

        if (elapsed <= 0) {
            return null
        }

        val used = current.cpuNanos - last.cpuNanos

        if (used < 0) {
            return null
        }

        val cores = HostMetrics.cpuCores()

        return ((used.toDouble() / elapsed.toDouble()) / cores * 100.0).coerceIn(0.0, 100.0)
    }

    /** Resets the CPU baseline, so a restarted process does not inherit the old one's counter. */
    override fun reset() {
        previous = null
    }

    companion object {
        /** Resident set size in bytes, or null when this host cannot be asked. */
        fun residentBytes(pid: Long): Long? {
            if (HostPlatform.isLinux) {
                readProcStatus(pid)?.let { return it }
            }

            if (HostPlatform.isWindows) {
                return null
            }

            return readPs(pid)
        }

        private fun readProcStatus(pid: Long): Long? {
            val status = File("/proc/$pid/status")

            if (!status.isFile) {
                return null
            }

            return try {
                status.useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }
                        ?.split(Regex("\\s+"))
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?.times(1024L)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun readPs(pid: Long): Long? = try {
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

            process.waitFor(5, TimeUnit.SECONDS)

            output.toLongOrNull()?.times(1024L)
        } catch (_: Exception) {
            null
        }
    }
}
