package com.panomc.node.console

/**
 * Joins the history read off disk to the scrollback still in memory.
 *
 * The two sources overlap: everything the ring holds was also written to the log file, so a naive
 * concatenation shows the last few hundred lines twice. The overlap is always a suffix of the file
 * lines matching a prefix of the ring lines, so the join is the longest such match -- longest, not
 * first, because a server that prints the same line every tick would otherwise match on one line
 * and drop nothing.
 */
object ConsoleHistoryMerge {
    /** How far into the ring an overlap is looked for. Beyond this the ring is the whole file. */
    const val MAX_OVERLAP = ConsoleBuffer.RING_BUFFER_SIZE

    fun merge(fileLines: List<ConsoleLine>, ringLines: List<ConsoleLine>, limit: Int): List<ConsoleLine> {
        if (limit <= 0) {
            return emptyList()
        }

        if (fileLines.isEmpty()) {
            return ringLines.takeLast(limit)
        }

        if (ringLines.isEmpty()) {
            return fileLines.takeLast(limit)
        }

        val max = minOf(fileLines.size, ringLines.size, MAX_OVERLAP)

        var overlap = 0

        for (k in max downTo 1) {
            if (matches(fileLines, ringLines, k)) {
                overlap = k

                break
            }
        }

        return (fileLines.subList(0, fileLines.size - overlap) + ringLines).takeLast(limit)
    }

    /** Whether the last [k] file lines are the first [k] ring lines, by text alone. */
    private fun matches(fileLines: List<ConsoleLine>, ringLines: List<ConsoleLine>, k: Int): Boolean {
        val offset = fileLines.size - k

        for (i in 0 until k) {
            if (fileLines[offset + i].m != ringLines[i].m) {
                return false
            }
        }

        return true
    }
}
