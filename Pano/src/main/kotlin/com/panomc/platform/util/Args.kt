package com.panomc.platform.util

/**
 * Tiny argument/flag helper.
 *
 * Flags are matched with one or two leading dashes, so `-dev` and `--dev`, `-nogui` and
 * `--nogui` are the same flag. The name itself is case-sensitive.
 */
object Args {
    fun hasFlag(args: Array<String>, flag: String): Boolean = args.any { isFlag(it, flag) }

    fun hasFlag(args: List<String>, flag: String): Boolean = args.any { isFlag(it, flag) }

    /** True when [arg] is [flag] written with one or two leading dashes. */
    fun isFlag(arg: String, flag: String): Boolean {
        val name = flagName(flag)

        return name.isNotEmpty() && (arg == "-$name" || arg == "--$name")
    }

    private fun flagName(flag: String): String = flag.removePrefix("--").removePrefix("-")
}
