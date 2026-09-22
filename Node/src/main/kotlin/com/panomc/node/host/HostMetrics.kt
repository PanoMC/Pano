package com.panomc.node.host

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * The host numbers a node reports, taken from the JDK and nothing else.
 *
 * No OSHI, deliberately: it is a native-loading dependency that would have to be shipped for every
 * operating system and architecture the node supports, in exchange for figures the JDK's own
 * management bean already provides. Anything the bean cannot answer is reported as null rather
 * than guessed -- a wrong CPU reading in the panel is worse than a blank one.
 */
object HostMetrics {
    private val osBean = ManagementFactory.getOperatingSystemMXBean()

    private val sunBean: com.sun.management.OperatingSystemMXBean? =
        osBean as? com.sun.management.OperatingSystemMXBean

    fun cpuCores(): Int = osBean.availableProcessors.coerceAtLeast(1)

    /** System-wide CPU usage as a 0..100 percentage, or null when the JVM cannot measure it. */
    fun cpuPercent(): Double? {
        val load = try {
            sunBean?.cpuLoad ?: return null
        } catch (_: Throwable) {
            return null
        }

        if (load.isNaN() || load < 0) {
            return null
        }

        return load * 100.0
    }

    fun memTotal(): Long = try {
        sunBean?.totalMemorySize ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /**
     * Memory in use the way `free` and every system monitor count it: total minus what is available.
     *
     * The bean's free size is Linux's `MemFree`, which leaves the page cache counted as used -- on a
     * host that has been up for a while that is gigabytes the kernel hands back the moment anything
     * asks, and the panel showed 29.8 GB used where `free` said 26. So on Linux `MemAvailable` wins,
     * but only when `/proc/meminfo` describes the same memory the bean does: inside a container the
     * bean reports the cgroup limit while `/proc/meminfo` is still the whole host's.
     */
    fun memUsed(): Long = try {
        val total = sunBean?.totalMemorySize ?: return 0L
        val free = linuxAvailable(total) ?: sunBean.freeMemorySize

        (total - free).coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }

    /** `MemAvailable` in bytes when `/proc/meminfo` has it and its `MemTotal` matches [total]. */
    internal fun linuxAvailable(total: Long, meminfo: File = File("/proc/meminfo")): Long? {
        if (!meminfo.isFile) {
            return null
        }

        val fields = try {
            meminfo.readLines().associate { line ->
                val name = line.substringBefore(':').trim()
                val kib = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()

                name to kib
            }
        } catch (_: Exception) {
            return null
        }

        val memTotal = fields["MemTotal"]?.times(1024) ?: return null
        val available = fields["MemAvailable"]?.times(1024) ?: return null

        // The kernel's MemTotal and the JVM's figure agree to the page on bare metal; anything more
        // than a percent apart is a cgroup limit, where the host-wide number would be wrong.
        if (kotlin.math.abs(memTotal - total) > total / 100) {
            return null
        }

        return available.coerceIn(0L, total)
    }

    /** Size of the volume the data directory sits on, which is the disk a server install fills. */
    fun diskTotal(dataDir: File): Long = fileStore(dataDir)?.totalSpace ?: 0L

    fun diskUsed(dataDir: File): Long {
        val store = fileStore(dataDir) ?: return 0L

        return (store.totalSpace - store.usableSpace).coerceAtLeast(0L)
    }

    private fun fileStore(dataDir: File) = try {
        var path = dataDir.absoluteFile

        while (!path.exists() && path.parentFile != null) {
            path = path.parentFile
        }

        Files.getFileStore(path.toPath())
    } catch (_: Exception) {
        null
    }
}
