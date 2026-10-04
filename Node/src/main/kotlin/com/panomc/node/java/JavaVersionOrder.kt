package com.panomc.node.java

/**
 * Orders Java version strings from different vendors and different eras.
 *
 * Three shapes have to compare sensibly against each other: Temurin's `21.0.12.1+1`, Zulu's
 * `21.0.8+9` (built here from its `java_version` array and build number), and Java 8's
 * `1.8.0_504-b01` next to Zulu's `8.0.462+8` for the same line. So a version is reduced to its
 * numbers in order, the legacy `1.` prefix is dropped, and the lists are compared element by
 * element with a missing element counting as zero. Anything with no number in it at all sorts
 * below everything that has one, which is the answer for a runtime whose version is unknown.
 */
object JavaVersionOrder : Comparator<String?> {
    override fun compare(a: String?, b: String?): Int {
        val left = numbers(a)
        val right = numbers(b)

        if (left.isEmpty() || right.isEmpty()) {
            return left.isNotEmpty().compareTo(right.isNotEmpty())
        }

        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = (left.getOrNull(index) ?: 0L).compareTo(right.getOrNull(index) ?: 0L)

            if (difference != 0) {
                return difference
            }
        }

        return 0
    }

    /** Whether [candidate] is a strictly newer release than [installed]. */
    fun isNewer(candidate: String?, installed: String?): Boolean = compare(candidate, installed) > 0

    /** The numbers in [version], in order, with Java 8's `1.` prefix removed. */
    fun numbers(version: String?): List<Long> {
        val parts = Regex("\\d+").findAll(version ?: return emptyList())
            .mapNotNull { it.value.toLongOrNull() }
            .toList()

        return if (parts.size > 1 && parts[0] == 1L) parts.drop(1) else parts
    }
}
