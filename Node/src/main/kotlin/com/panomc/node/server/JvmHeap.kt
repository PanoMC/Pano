package com.panomc.node.server

import kotlin.math.max
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
}
