package com.panomc.node.agent

/**
 * Java command lines as a Pano Agent's first run reads them (SM-76): the admin's own answers, and
 * the start script it prefills them from. Plain JDK, for the launcher.
 */
object JvmArgs {
    /** The least memory the questions accept for a server, in megabytes. */
    const val MIN_MEMORY_MB = 512

    /** The most: what Pano's create and startup APIs accept. */
    const val MAX_MEMORY_MB = 1024 * 1024

    /** What a server gets when nothing says otherwise. */
    const val DEFAULT_MEMORY_MB = 2048

    /**
     * [text] split into arguments the way a shell would, without expanding anything: whitespace
     * separates, single quotes keep everything, double quotes keep everything but a backslash
     * before a double quote or a backslash. Outside quotes a backslash only escapes a quote, a
     * backslash or whitespace, so a Windows path keeps its backslashes. With [windows] a backslash
     * is never special. Null when a quote is left open.
     */
    fun split(text: String, windows: Boolean = false): List<String>? {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var index = 0

        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)

            when {
                quote == '\'' -> if (char == '\'') quote = null else current.append(char)

                quote == '"' -> when {
                    char == '"' -> quote = null
                    char == '\\' && !windows && (next == '"' || next == '\\') -> {
                        current.append(next)
                        index++
                    }

                    else -> current.append(char)
                }

                char == '\'' || char == '"' -> {
                    quote = char
                    inToken = true
                }

                char.isWhitespace() -> if (inToken) {
                    result.add(current.toString())
                    current.setLength(0)
                    inToken = false
                }

                char == '\\' && !windows && next != null && (next == '"' || next == '\'' || next == '\\' || next.isWhitespace()) -> {
                    current.append(next)
                    inToken = true
                    index++
                }

                else -> {
                    current.append(char)
                    inToken = true
                }
            }

            index++
        }

        if (quote != null) {
            return null
        }

        if (inToken) {
            result.add(current.toString())
        }

        return result
    }

    /** [args] as one line [split] reads back the same: arguments with spaces or quotes are quoted. */
    fun join(args: List<String>): String = args.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            arg
        }
    }

    /** Whether [arg] sets the heap (`-Xms…`, `-Xmx…`): memory is a question of its own. */
    fun isHeapFlag(arg: String): Boolean = arg.startsWith("-Xmx") || arg.startsWith("-Xms")

    /**
     * [args] without what the agent sets itself, and what was left out: `-Xms`/`-Xmx` (the memory
     * question) and `-jar` with everything after it (the jar question, and the server's own
     * arguments -- after `-jar` nothing is a Java argument any more).
     */
    fun withoutLaunchFlags(args: List<String>): Pair<List<String>, List<String>> {
        val jarAt = args.indexOf("-jar").takeIf { it >= 0 } ?: args.size
        val before = args.subList(0, jarAt)

        val kept = before.filterNot { isHeapFlag(it) }
        val dropped = before.filter { isHeapFlag(it) } + args.subList(jarAt, args.size)

        return kept to dropped
    }

    /**
     * An `-Xmx` value as the JVM reads it (`4G`, `4096m`, `512k`, or bytes with no suffix), in
     * megabytes; null when it is not one.
     */
    fun heapMb(value: String): Int? {
        val match = Regex("^([0-9]+)([kKmMgGtT]?)$").matchEntire(value.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null

        val mb = when (match.groupValues[2].lowercase()) {
            "t" -> amount * 1024 * 1024
            "g" -> amount * 1024
            "m" -> amount
            "k" -> amount / 1024
            else -> amount / (1024 * 1024)
        }

        return mb.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    /** The last `-Xmx` in [args], in megabytes (the JVM takes the last one too). */
    fun heapMbOf(args: List<String>): Int? =
        args.lastOrNull { it.startsWith("-Xmx") }?.let { heapMb(it.removePrefix("-Xmx")) }

    /**
     * What the admin typed for the memory question, in megabytes: `4G`, `4096M`, `4096` (a bare
     * number is megabytes), `1.5G`, `4GB`, any case, spaces ignored, an `-Xmx` in front forgiven.
     * Null when it is none of those.
     */
    fun memoryAnswerMb(answer: String): Int? {
        val text = answer.replace(" ", "").removePrefix("-Xmx").removePrefix("-xmx")
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)([mMgGtT]?)[bB]?$").matchEntire(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null

        val mb = when (match.groupValues[2].lowercase()) {
            "t" -> amount * 1024 * 1024
            "g" -> amount * 1024
            else -> amount
        }

        return mb.takeIf { it >= 1 && it <= Int.MAX_VALUE }?.toLong()?.toInt()
    }

    /** [mb] the way the questions show it: `4G` when it is whole gigabytes, `3584M` otherwise. */
    fun formatMemory(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024}G" else "${mb}M"
}
