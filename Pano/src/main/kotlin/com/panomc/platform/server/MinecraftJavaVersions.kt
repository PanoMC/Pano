package com.panomc.platform.server

/**
 * Which Java a Minecraft version needs, for the panel to show before anything is installed.
 *
 * The node makes the real decision -- it is the machine that knows what is installed on it -- and
 * it holds its own copy of this ladder in `com.panomc.node.server.MinecraftJavaVersions`. The two
 * are deliberately separate: `pano-node.jar` is a standalone daemon that must keep working against
 * a Pano it was not built with, so it cannot share code with the platform. Change one and change
 * the other.
 *
 * The ladder itself is Mojang's: 1.20.5 moved the server to Java 21, 1.17 to Java 16/17, and
 * everything before 1.17 runs on 8 and breaks on 17+.
 */
object MinecraftJavaVersions {
    /** Lowest Java that can run this version at all. */
    fun minimumFor(version: String?): Int {
        val parsed = parse(version) ?: return MODERN_JAVA

        // Minecraft's 2026 versions are `26.x`, not `1.x`; anything that is not a `1.x` is newer
        // than every version this ladder has a rule for.
        // Minecraft 26.1 (the first 2026-numbered release) moved the server to Java 25 -- Paper
        // refuses to boot on anything older with "Minecraft 26.1 and newer requires running the
        // server with Java 25 or above". Proxies keep their own numbering (Velocity 4.x) and are
        // modern but not that new; the jar's own class-file check catches anything stricter.
        if (parsed.first >= 26) {
            return 25
        }

        if (parsed.first != 1) {
            return MODERN_JAVA
        }

        return when {
            atLeast(parsed, 1, 20, 5) -> MODERN_JAVA
            atLeast(parsed, 1, 17, 0) -> 17
            atLeast(parsed, 1, 16, 5) -> 16
            else -> LEGACY_JAVA
        }
    }

    /** Highest Java that still runs this version, or null when there is no known ceiling. */
    fun maximumFor(version: String?): Int? {
        val parsed = parse(version) ?: return null

        if (parsed.first != 1) {
            return null
        }

        // 1.16.5 and older load classes through a Guava/ASM combination that Java 17 removed the
        // ground from under; they do not start on it however new the runtime is.
        return if (atLeast(parsed, 1, 17, 0)) null else 16
    }

    /**
     * The Java the panel names as the automatic choice for [version].
     *
     * The node may still land on a different runtime, because what is installed is a question about
     * a host rather than about a version -- but both start from this number, so on a host that has
     * it the panel and the node agree.
     */
    fun recommendedFor(version: String?): Int = minimumFor(version)

    /** A runtime chosen out of a host's installed majors, with the sentence explaining why. */
    data class Choice(val major: Int, val reason: String)

    /**
     * Picks a runtime out of what a host actually has.
     *
     * The minimum wins whenever it is installed. Newest-wins was the obvious rule and the wrong
     * one: Paper 1.21.8 asks for Java 21, starts happily on 26 and then dies in native code on
     * shutdown, because "runs" and "is supported" are not the same question. So the order is the
     * minimum, then the lowest runtime inside the version's band, then -- for an old server on a
     * modern host with nothing inside that band -- the newest runtime still old enough to load it,
     * and only then the lowest one above the minimum, which beats refusing to start at all.
     */
    fun pick(available: Collection<Int>, version: String?): Int? = choose(available, version)?.major

    /** [pick] with its reasoning kept, so a launch log can say why this runtime and not another. */
    fun choose(available: Collection<Int>, version: String?): Choice? {
        if (available.isEmpty()) {
            return null
        }

        val minimum = minimumFor(version)
        val maximum = maximumFor(version)
        val named = version ?: "this server"

        val inBand = available.filter { it >= minimum && (maximum == null || it <= maximum) }

        inBand.minOrNull()?.let { chosen ->
            val reason = if (chosen == minimum) {
                "Java $minimum is the minimum $named needs and this host has it"
            } else {
                "Java $chosen is the lowest runtime installed here that $named supports " +
                    "(it needs at least Java $minimum)"
            }

            return Choice(chosen, reason)
        }

        if (maximum != null) {
            available.filter { it <= maximum }.maxOrNull()?.let { chosen ->
                return Choice(
                    chosen,
                    "Java $chosen is the newest runtime installed here old enough for $named, " +
                        "which does not start on anything past Java $maximum"
                )
            }
        }

        return available.filter { it >= minimum }.minOrNull()?.let { chosen ->
            Choice(
                chosen,
                "Java $chosen is the lowest runtime installed here at or above the Java $minimum " +
                    "$named needs; nothing inside its supported range is installed"
            )
        }
    }

    /** What every Minecraft version from 1.20.5 on needs. */
    const val MODERN_JAVA = 21

    /** What everything before 1.16.5 was built for. */
    const val LEGACY_JAVA = 8

    private fun parse(version: String?): Triple<Int, Int, Int>? {
        val cleaned = version?.trim()?.takeIf { it.isNotBlank() } ?: return null

        // "1.20.1-rc1", "1.21.11-pre5" and "3.5.1-SNAPSHOT" all carry the version in front.
        val parts = cleaned.substringBefore('-').split('.')

        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

        return Triple(major, minor, patch)
    }

    private fun atLeast(version: Triple<Int, Int, Int>, major: Int, minor: Int, patch: Int): Boolean {
        if (version.first != major) {
            return version.first > major
        }

        if (version.second != minor) {
            return version.second > minor
        }

        return version.third >= patch
    }
}
