package com.panomc.platform.gate

import com.panomc.platform.ApiLevel

/** What the API level gate says about one declared level. */
enum class Verdict {
    /** `MIN_SUPPORTED <= level <= CURRENT`: the resource may run. */
    OK,

    /** Below the lowest supported level, and every resource that declares nothing (level 0). */
    TOO_OLD,

    /** Above the level this Pano implements: the resource needs a newer Pano. */
    TOO_NEW
}

/**
 * Gate 1 of the compatibility gate (doc 04 section 7): a pure decision from a declared level, with no side effect.
 * The callers decide what a refusal means (a plugin stays disabled in memory, a theme falls back to the bundled one).
 */
object ApiLevelGate {
    /** The verdict for [level] against the levels of this Pano. An absent declaration is level 0. */
    fun check(level: Int): Verdict = check(level, ApiLevel.MIN_SUPPORTED, ApiLevel.CURRENT)

    /** The verdict for [level] against an explicit range, for the update plan and for tests. */
    fun check(level: Int, minSupported: Int, current: Int): Verdict = when {
        level < minSupported -> Verdict.TOO_OLD
        level > current -> Verdict.TOO_NEW
        else -> Verdict.OK
    }
}
