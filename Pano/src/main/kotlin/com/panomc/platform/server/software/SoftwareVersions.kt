package com.panomc.platform.server.software

/**
 * Tells a released version from a development one, and says which one to install by default.
 *
 * Every upstream in the catalog publishes its unfinished work next to its releases, and all of
 * them do it by suffix: PaperMC's proxies ship `3.6.0-SNAPSHOT`, Mojang ships `1.21-pre1` and
 * `1.21-rc1`, and a project that has moved to semantic versioning ships `-alpha`/`-beta`. Nothing
 * about the *order* of those lists says which is which — a snapshot is newer than every release,
 * so "the first one" is exactly the wrong answer.
 *
 * That was not a hypothetical: a Velocity created with no version picked `velocity 4.2.1-SNAPSHOT`,
 * a build compiled for Java 25, and the proxy crash-looped on a host whose newest runtime was 21.
 *
 * Pure and suffix-based on purpose. Asking an upstream whether a build is stable costs a request
 * per version, and the name is right often enough to be the first filter; where it is not — the
 * PaperMC proxies, where a version can exist with nothing but experimental builds — the catalog
 * confirms the pick against the builds endpoint on top of this.
 */
object SoftwareVersions {
    /**
     * Suffixes that mark a version as not-yet-released.
     *
     * Matched anywhere after a separator rather than only at the end, because the shapes differ:
     * `1.20.1-R0.1-SNAPSHOT`, `26.3-rc-3` and `1.21-pre1` all carry the marker in a different
     * place, and all three mean the same thing.
     */
    private val PRERELEASE_MARKERS = listOf("snapshot", "pre", "rc", "beta", "alpha", "experimental")

    /** Whether [version] is a released build rather than a snapshot or a pre-release. */
    fun isStable(version: String): Boolean = !isPrerelease(version)

    /** Whether [version] carries one of the development suffixes. */
    fun isPrerelease(version: String): Boolean {
        val cleaned = version.trim().lowercase()

        if (cleaned.isEmpty()) {
            return false
        }

        // Only the parts after a separator are looked at: the marker is always a suffix of its
        // own, and matching the bare text would make "1.21-alphabet" or a version group called
        // "prerelease-free" a false positive.
        return cleaned.split('-', '_', '+')
            .drop(1)
            .any { part -> PRERELEASE_MARKERS.any { marker -> marks(part, marker) } }
    }

    /**
     * Whether [part] is [marker], optionally followed by a number.
     *
     * The letter check is what keeps `1.21-alphabet` a release while `1.21-pre1`, `-rc-3` and
     * `-beta.2` are not: a marker that ran into more letters is a different word, and a version
     * quietly reclassified by a substring match would be a worse bug than the one this fixes.
     */
    private fun marks(part: String, marker: String): Boolean {
        if (part == marker) {
            return true
        }

        return part.startsWith(marker) && part.getOrNull(marker.length)?.isLetter() == false
    }

    /**
     * [versions] with the releases first, each group keeping the order the upstream gave it.
     *
     * The snapshots are kept rather than dropped: somebody installing a 1.21.12 snapshot on
     * purpose is a legitimate thing to want, and a wizard that simply cannot offer it is a worse
     * answer than one that does not offer it *first*.
     */
    fun stableFirst(versions: List<String>): List<String> {
        val (stable, prerelease) = versions.partition { isStable(it) }

        return stable + prerelease
    }

    /**
     * The version to install when nobody picked one.
     *
     * The newest release, or — for a software that has never published one — the newest build
     * there is, because "no version available" would be a worse answer than a snapshot somebody
     * can at least see the name of.
     */
    fun recommended(versions: List<String>): String? =
        versions.firstOrNull { isStable(it) } ?: versions.firstOrNull()

    /**
     * [versions] sorted newest first, comparing each dotted part as a number.
     *
     * For the upstreams that hand back no order at all: PaperMC's version *groups* are the keys of
     * a JSON object, and SpigotMC's revision list is a directory index sorted as text -- where
     * "1.9" comes after "1.10" and 26.3 lands in the middle of the ones.
     */
    fun newestFirst(versions: List<String>): List<String> = versions.sortedWith(NEWEST_FIRST)

    /** [newestFirst] as a comparator, for callers sorting something that is not a bare list. */
    val NEWEST_FIRST: Comparator<String> = Comparator { left, right -> VERSION_ORDER.compare(right, left) }

    /**
     * Orders `1.20` before `1.21` and both before `26.3`, by comparing numbers as numbers.
     *
     * Anything non-numeric sorts first rather than throwing: a name Pano has never seen is still
     * worth listing, just not worth putting at the top of a newest-first list.
     */
    private val VERSION_ORDER = Comparator<String> { left, right ->
        val leftParts = left.split('.')
        val rightParts = right.split('.')

        var result = 0

        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val leftPart = leftParts.getOrNull(index)?.toIntOrNull() ?: -1
            val rightPart = rightParts.getOrNull(index)?.toIntOrNull() ?: -1

            if (leftPart != rightPart) {
                result = leftPart.compareTo(rightPart)

                break
            }
        }

        if (result != 0) result else left.compareTo(right)
    }
}
