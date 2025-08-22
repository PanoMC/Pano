package com.panomc.platform.util

/**
 * Tiny argument/flag helper.
 */
object Args {
    fun hasFlag(args: Array<String>, flag: String): Boolean {
        return args.any { it.equals(flag, ignoreCase = false) }
    }
}