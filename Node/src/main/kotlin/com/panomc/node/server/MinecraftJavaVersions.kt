package com.panomc.node.server

/**
 * Which Java a Minecraft version runs on, used when Pano leaves the choice to this host.
 *
 * `INSTALL_SERVER` may arrive with no `javaMajor` at all, which is the panel's "automatic": Pano
 * knows the Minecraft version but not what is installed here, and this daemon knows both. The
 * ladder is Mojang's -- 1.20.5 moved the server to Java 21, 1.17 to 16/17, and everything older
 * than 1.17 fails to start on 17+ -- and Pano keeps a copy of it in
 * `com.panomc.platform.server.MinecraftJavaVersions` for what it shows in the wizard. The daemon
 * ships as its own jar against Pano versions it was not built with, so the two cannot share code;
 * change one and change the other.
 */
object MinecraftJavaVersions {
    /** What every Minecraft version from 1.20.5 on needs. */
    const val MODERN_JAVA = 21

    /** What everything before 1.16.5 was built for. */
    const val LEGACY_JAVA = 8

    /** Lowest Java that can run this version at all. */
    fun minimumFor(version: String?): Int {
        val parsed = parse(version) ?: return MODERN_JAVA

        // Minecraft's 2026 versions are `26.x`, and a proxy's version is its own number entirely;
        // neither is a `1.x` this ladder has a rule for, and both are modern.
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

        return if (atLeast(parsed, 1, 17, 0)) null else 16
    }

    /** A runtime chosen out of this host's installed majors, with the sentence explaining why. */
    data class Choice(val major: Int, val reason: String)

    /**
     * Picks a runtime out of the majors this host actually has.
     *
     * The minimum wins whenever it is installed. Newest-wins was the obvious rule and the wrong
     * one: Paper 1.21.8 asks for Java 21, starts happily on 26 and then dies in native code on
     * shutdown, because "runs" and "is supported" are not the same question. So the order is the
     * minimum, then the lowest runtime inside the version's band, then -- an old server on a host
     * whose newest Java is far past what it can load -- the newest one still old enough to start
     * it, and only then the lowest one above the minimum, which beats refusing to start at all.
     */
    fun pick(available: Collection<Int>, version: String?, atLeast: Int? = null): Int? =
        choose(available, version, atLeast)?.major

    /**
     * [pick] with its reasoning kept, so the launch log can say why this runtime and not another.
     *
     * [atLeast] is what the jar itself asks for, read from its class files by [JarJavaRequirement].
     * It raises the floor and, when it is higher than the ladder's own ceiling, removes that
     * ceiling: the ladder is a rule about a Minecraft version, the jar is the thing that has to
     * load, and a jar compiled for Java 25 does not start on 21 whatever the version number says.
     */
    fun choose(available: Collection<Int>, version: String?, atLeast: Int? = null): Choice? {
        if (available.isEmpty()) {
            return null
        }

        val ladder = minimumFor(version)
        val required = atLeast?.takeIf { it > 0 } ?: 0
        val minimum = maxOf(ladder, required)
        val maximum = maximumFor(version)?.takeIf { it >= minimum }
        val named = version ?: "this server"
        val note = if (required > ladder) " (its jar is compiled for Java $required)" else ""

        val inBand = available.filter { it >= minimum && (maximum == null || it <= maximum) }

        inBand.minOrNull()?.let { chosen ->
            val reason = if (chosen == minimum) {
                "Java $minimum is the minimum $named needs and this host has it$note"
            } else {
                "Java $chosen is the lowest runtime installed here that $named supports " +
                    "(it needs at least Java $minimum)$note"
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
                    "$named needs; nothing inside its supported range is installed$note"
            )
        }
    }

    private fun parse(version: String?): Triple<Int, Int, Int>? {
        val cleaned = version?.trim()?.takeIf { it.isNotBlank() } ?: return null

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
