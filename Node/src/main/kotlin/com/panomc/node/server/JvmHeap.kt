package com.panomc.node.server

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How much of a server's memory setting its JVM heap gets.
 *
 * The setting is the whole server process, the way an admin reads "1.5 GB of RAM": the heap is
 * only part of what a JVM takes, and metaspace, thread stacks, the code cache, GC bookkeeping and
 * Netty's direct buffers come on top of it -- 300 to 600 MB for a Minecraft server. Handing the
 * heap the whole setting (`-Xmx` = setting, as before) made a 1.5 GB server use 2.1 GB, and under
 * the Docker runtime, whose `--memory` is the setting too, a container the kernel kills as soon as
 * the heap fills up.
 *
 * So the heap gets the setting minus a share for the rest: a quarter of it, at least
 * [MIN_OVERHEAD_MB] and at most [MAX_OVERHEAD_MB], and never less than half the setting, so a
 * tiny allowance is still a server rather than a JVM that cannot start.
 */
object JvmHeap {
    const val OVERHEAD_SHARE = 0.25
    const val MIN_OVERHEAD_MB = 384
    const val MAX_OVERHEAD_MB = 1024

    /** The `-Xmx`/`-Xms` in megabytes for a server whose memory setting is [memoryMb]. */
    fun heapMb(memoryMb: Int): Int {
        if (memoryMb <= 0) {
            return memoryMb
        }

        val overhead = (memoryMb * OVERHEAD_SHARE).roundToInt().coerceIn(MIN_OVERHEAD_MB, MAX_OVERHEAD_MB)

        return max(memoryMb - overhead, memoryMb / 2)
    }

    /** Where the heap starts: a quarter of [maxHeapMb], at least [MIN_INITIAL_HEAP_MB]. */
    const val MIN_INITIAL_HEAP_MB = 256

    /**
     * The `-Xms` for a heap that may grow to [maxHeapMb].
     *
     * Small on purpose. With `-Xms` equal to `-Xmx` the JVM took its whole heap from the host at
     * start and never gave any of it back, so a server that used 500 MB held 1.8 GB for its whole
     * life and its memory chart was a flat line. Starting at a quarter, the heap grows as the
     * server needs it and -- with [returnMemoryFlags] -- shrinks again when it idles.
     */
    fun initialHeapMb(maxHeapMb: Int): Int =
        if (maxHeapMb <= 0) maxHeapMb else min(maxHeapMb, max(MIN_INITIAL_HEAP_MB, maxHeapMb / 4))

    /**
     * The flags that make an idle JVM hand memory back to the host (G1, JEP 346): a periodic
     * collection when nothing else has collected for 30 s, and free-ratio bounds that let the heap
     * shrink after it. Java 12 introduced the first one and an older JVM refuses to start on an
     * option it does not know, so they are only added for a runtime known to be new enough.
     */
    fun returnMemoryFlags(javaMajor: Int?): List<String> =
        if (javaMajor != null && javaMajor >= RETURN_MEMORY_MIN_JAVA) {
            listOf("-XX:G1PeriodicGCInterval=30000", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40")
        } else {
            emptyList()
        }

    const val RETURN_MEMORY_MIN_JAVA = 12
}
